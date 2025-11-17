package mcp.http4s.session

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{Constants, ErrorData, InitializeRequest, JsonRpcRequest, JsonRpcResponse, RequestId}
import mcp.server.McpServer
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.*
import org.typelevel.ci.*

/** Request headers for MCP HTTP transport.
  *
  * @param sessionId
  *   Optional session ID from Mcp-Session-Id header
  * @param protocolVersion
  *   Optional protocol version from MCP-Protocol-Version header
  * @param lastEventId
  *   Optional last event ID for SSE reconnection
  * @param accept
  *   Content types accepted by client
  */
case class RequestHeaders(
    sessionId: Option[SessionId],
    protocolVersion: Option[String],
    lastEventId: Option[EventId],
    accept: List[String]
)

/** HTTP routes for MCP protocol over HTTP/SSE.
  *
  * Implements the MCP 2025-06-18 spec with:
  *   - Single /mcp endpoint for all operations
  *   - Session management via Mcp-Session-Id header
  *   - Protocol version validation
  *   - SSE reconnection support
  */
object McpHttpRoutes {

  /** Create HTTP routes for MCP server.
    *
    * @param server
    *   The MCP server instance
    * @param sessionManager
    *   Session manager for handling client sessions
    * @param enableSessions
    *   If true, uses session-based mode (multi-client). If false, sessionless mode (single client).
    * @return
    *   HTTP routes to mount in http4s server
    */
  def routes[F[_]: Async](
      server: McpServer[F],
      sessionManager: SessionManager[F],
      enableSessions: Boolean
  ): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {

      case req @ POST -> Root / "mcp" =>
        extractHeaders(req).flatMap {
          case Left(error) =>
            jsonRpcError[F](None, Constants.INVALID_REQUEST, error)

          case Right(headers) =>
            val sessionId = if enableSessions then headers.sessionId else None
            sessionId match {
              case Some(id) =>
                sessionManager.getSession(Some(id)).flatMap {
                  case Some(_) =>
                    for {
                      errorOpt <- validateProtocolVersion(headers)
                      response <- errorOpt match {
                        case Some(error) =>
                          jsonRpcError[F](None, Constants.INVALID_REQUEST, error)
                        case None =>
                          sessionManager.updateActivity(Some(id)) >>
                            handlePostMessage(req, Some(id), sessionManager)
                      }
                    } yield response
                  case None =>
                    jsonRpcError[F](None, Constants.INVALID_REQUEST, "Session expired or invalid")
                }

              case None =>
                // No session - must be initialize or sessionless mode
                handleInitializeOrSessionless(req, server, sessionManager, enableSessions)
            }

        }

      case req @ GET -> Root / "mcp" =>
        extractHeaders(req).flatMap {
          case Left(error) =>
            jsonRpcError[F](None, Constants.INVALID_REQUEST, error)

          case Right(headers) =>
            val sessionId = if enableSessions then headers.sessionId else None
            sessionManager.getSession(sessionId).flatMap {
              case Some(_) =>
                for {
                  errorOpt <- validateProtocolVersion(headers)
                  response <- errorOpt match {
                    case Some(error) =>
                      jsonRpcError[F](None, Constants.INVALID_REQUEST, error)
                    case None =>
                      createPersistentJsonStream(sessionId, headers.lastEventId, sessionManager)
                  }
                } yield response
              case None =>
                // Auto-create session if no session ID provided (for SSE-only mode)
                if sessionId.isEmpty && enableSessions then
                  for {
                    newSessionId <- sessionManager.createSession(enableSessions, server)
                    response <- createPersistentJsonStream(newSessionId, headers.lastEventId, sessionManager).flatMap { baseResponse =>
                      // Add session ID header to response
                      newSessionId match {
                        case Some(sid) =>
                          Async[F].pure(baseResponse.putHeaders(Header.Raw(ci"Mcp-Session-Id", sid.value)))
                        case None =>
                          Async[F].pure(baseResponse)
                      }
                    }
                  } yield response
                else if sessionId.isDefined then jsonRpcError[F](None, Constants.INVALID_REQUEST, "Session expired")
                else jsonRpcError[F](None, Constants.INVALID_REQUEST, "Missing Mcp-Session-Id header")
            }
        }

      case req @ DELETE -> Root / "mcp" =>
        extractHeaders(req).flatMap {
          case Left(error) =>
            jsonRpcError[F](None, Constants.INVALID_REQUEST, error)

          case Right(headers) =>
            headers.sessionId match {
              case Some(sessionId) =>
                sessionManager.removeSession(Some(sessionId)) >> NoContent()
              case None =>
                jsonRpcError[F](None, Constants.INVALID_REQUEST, "Missing Mcp-Session-Id header")
            }
        }
    }
  }

  /** Extract and validate HTTP headers. */
  private def extractHeaders[F[_]: Async](req: Request[F]): F[Either[String, RequestHeaders]] =
    Async[F].pure {
      val sessionId = req.headers
        .get(ci"Mcp-Session-Id")
        .map(h => SessionId.fromString(h.head.value))

      val protocolVersion = req.headers
        .get(ci"MCP-Protocol-Version")
        .map(_.head.value)

      val lastEventId = req.headers
        .get(ci"Last-Event-ID")
        .flatMap(h => EventId.fromString(h.head.value))

      val accept = req.headers
        .get(Accept.headerInstance.name)
        .map(_.head.value.split(",").map(_.trim).toList)
        .getOrElse(Nil)

      // Validate Accept header for GET requests (must include text/event-stream)

      if req.method == Method.GET then
        if accept.contains("text/event-stream") || accept.contains("*/*") then
          Right(RequestHeaders(sessionId, protocolVersion, lastEventId, accept))
        else Left("Accept header must include text/event-stream for GET requests")
      else Right(RequestHeaders(sessionId, protocolVersion, lastEventId, accept))
    }

  /** Validate protocol version header (required after initialize). */
  private def validateProtocolVersion[F[_]: Async](headers: RequestHeaders): F[Option[String]] =
    headers.protocolVersion match {
      case Some(version) if version == Constants.LATEST_PROTOCOL_VERSION =>
        Async[F].pure(None)
      case Some(version) =>
        Async[F].pure(Some(s"Unsupported protocol version: $version. Expected ${Constants.LATEST_PROTOCOL_VERSION}"))
      case None =>
        // First request (initialize) may not have version
        Async[F].pure(None)
    }

  /** Handle POST message processing.
    *
    * Parses JSON-RPC request, enqueues for processing, returns JSON response.
    */
  private def handlePostMessage[F[_]: Async](
      req: Request[F],
      sessionId: Option[SessionId],
      sessionManager: SessionManager[F]
  ): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val result = for {
      json <- req.as[Json].attempt.flatMap {
        case Left(error) =>
          Async[F].raiseError(new Exception(s"Failed to parse JSON: ${error.getMessage}"))
        case Right(j) => Async[F].pure(j)
      }
      jsonRpcReq <- Async[F].fromEither(json.as[JsonRpcRequest]).attempt.flatMap {
        case Left(error) =>
          Async[F].raiseError(new Exception(s"Failed to decode JsonRpcRequest: ${error.getMessage}"))
        case Right(req) =>
          Async[F].pure(req)
      }

      _ <- sessionManager.enqueueRequest(sessionId, jsonRpcReq)
      sessionStateOpt <- sessionManager.getSession(sessionId)

      response <- sessionStateOpt match {
        case Some(sessionState) =>
          val jsonStream = Stream
            .fromQueueNoneTerminated(sessionState.postResponseQueue)
            .take(1) // Take single response for this POST request
            .evalMap { msg =>
              val json = msg match {
                case ServerMessage.Response(r)     => r.asJson
                case ServerMessage.Request(r)      => r.asJson
                case ServerMessage.Notification(n) => n.asJson
              }
              Async[F].pure(json.deepDropNullValues.noSpaces)
            }
            .through(fs2.text.utf8.encode)

          Ok(
            jsonStream,
            `Content-Type`(MediaType.application.json),
            `Cache-Control`(CacheDirective.`no-cache`()),
            Connection(ci"keep-alive")
          )

        case None =>
          Async[F].raiseError(new Exception("Session not found"))
      }
    } yield response

    result.handleErrorWith { error =>
      for {
        requestId <- req.as[Json].attempt.flatMap {
          case Right(json) => Async[F].pure(json.hcursor.get[RequestId]("id").toOption)
          case Left(_)     => Async[F].pure(None)
        }
        response <- jsonRpcError[F](requestId, -32603, s"Internal error: ${error.getMessage}")
      } yield response
    }
  }

  /** Handle initialize request or sessionless mode operation.
    *
    * Creates session (if session-based), processes initialize request, returns session ID in header.
    */
  private def handleInitializeOrSessionless[F[_]: Async](
      req: Request[F],
      server: McpServer[F],
      sessionManager: SessionManager[F],
      enableSessions: Boolean
  ): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val result = for {
      // Parse request
      json <- req.as[Json].attempt
      _ <- json match {
        case Left(error) =>
          Async[F].raiseError(new Exception(s"Failed to parse JSON: ${error.getMessage}"))
        case Right(_) => Async[F].unit
      }
      parsedJson = json.toOption.get

      method <- Async[F].fromEither(parsedJson.hcursor.get[String]("method"))

      response <- method match {
        case "initialize" =>
          for {
            jsonRpcReq <- Async[F].fromEither(parsedJson.as[JsonRpcRequest])
            initReq <- jsonRpcReq.fromParam[InitializeRequest].liftTo[F]
            sessionIdOpt <- sessionManager.createSession(enableSessions, server)
            _ <- sessionManager.setCapabilities(sessionIdOpt, initReq.capabilities)
            _ <- sessionManager.enqueueRequest(sessionIdOpt, jsonRpcReq)
            sessionStateOpt <- sessionManager.getSession(sessionIdOpt)

            resp <- sessionStateOpt match {
              case Some(sessionState) =>
                val jsonStream = Stream
                  .fromQueueNoneTerminated(sessionState.postResponseQueue)
                  .take(1) // Take single initialize response
                  .evalMap { msg =>
                    val json = msg match {
                      case ServerMessage.Response(r)     => r.asJson
                      case ServerMessage.Request(r)      => r.asJson
                      case ServerMessage.Notification(n) => n.asJson
                    }
                    Async[F].pure(json.deepDropNullValues.noSpaces)
                  }
                  .through(fs2.text.utf8.encode)

                val baseResponse = Ok(
                  jsonStream,
                  `Content-Type`(MediaType.application.json),
                  `Cache-Control`(CacheDirective.`no-cache`()),
                  Connection(ci"keep-alive")
                )

                // Add Mcp-Session-Id header if session-based
                sessionIdOpt match {
                  case Some(sessionId) =>
                    baseResponse.map(
                      _.putHeaders(
                        Header.Raw(ci"Mcp-Session-Id", sessionId.value)
                      )
                    )
                  case None =>
                    baseResponse
                }

              case None =>
                Async[F].raiseError(new Exception("Failed to create session"))
            }
          } yield resp

        case _ =>
          Async[F].raiseError(new Exception(s"First request must be initialize, got: $method"))
      }
    } yield response

    result.handleErrorWith { error =>
      for {
        requestId <- req.as[Json].attempt.flatMap {
          case Right(json) => Async[F].pure(json.hcursor.get[RequestId]("id").toOption)
          case Left(_)     => Async[F].pure(None)
        }
        response <- jsonRpcError[F](requestId, -32603, s"Internal error: ${error.getMessage}")
      } yield response
    }
  }

  /** Create persistent SSE stream for GET requests. */
  private def createPersistentJsonStream[F[_]: Async](
      sessionId: Option[SessionId],
      lastEventId: Option[EventId],
      sessionManager: SessionManager[F]
  ): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    sessionManager.getSession(sessionId).flatMap {
      case Some(session) =>

        val replayStream: Stream[F, (EventId, ServerMessage)] = lastEventId match {
          case Some(eventId) => Stream.eval(sessionManager.getEventsSince(sessionId, eventId)).flatMap(events => Stream.emits(events))
          case None          => Stream.empty
        }

        val liveStream: Stream[F, (EventId, ServerMessage)] = Stream
          .fromQueueNoneTerminated(session.persistentQueue)
          .evalMap(msg => sessionManager.appendEvent(sessionId, msg).map(_ -> msg))

        val fullStream = (replayStream ++ liveStream)
          .map { case (id, msg) =>
            val json = msg match {
              case ServerMessage.Response(r)     => r.asJson
              case ServerMessage.Request(r)      => r.asJson
              case ServerMessage.Notification(n) => n.asJson
            }
            json.deepDropNullValues.noSpaces ++ "\n"
          }
          .through(fs2.text.utf8.encode)

        Ok(
          fullStream,
          `Content-Type`(MediaType.`text/event-stream`),
          `Cache-Control`(CacheDirective.`no-cache`()),
          Connection(ci"keep-alive")
        )

      case None =>
        jsonRpcError[F](None, Constants.INVALID_REQUEST, "Session not found")
    }
  }

  private def jsonRpcError[F[_]: Async](
      requestId: Option[RequestId],
      code: Int,
      message: String
  ): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val errorResponse = JsonRpcResponse.Error(
      jsonrpc = Constants.JSONRPC_VERSION,
      id = requestId,
      error = ErrorData(code = code, message = message)
    )

    val stream = Stream
      .eval(Async[F].pure(errorResponse.asJson.deepDropNullValues.noSpaces ++ "\n"))
      .through(fs2.text.utf8.encode)

    Ok(
      stream,
      `Content-Type`(MediaType.`text/event-stream`),
      `Cache-Control`(CacheDirective.`no-cache`()),
      Connection(ci"keep-alive")
    )
  }
}
