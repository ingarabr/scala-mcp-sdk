package mcp.http4s.session

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{Constants, ErrorData, InitializeRequest, JsonRpcRequest, JsonRpcResponse, McpError, RequestId}
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

/** HTTP routes for MCP protocol using Streamable HTTP transport.
  *
  * Implements the MCP 2025-11-25 spec with:
  *   - Session management via Mcp-Session-Id header
  *   - Protocol version validation
  *   - SSE reconnection support
  *
  * Routes match at Root. Use http4s Router to mount at a custom path:
  * {{{
  * val mcpRoutes = McpHttpRoutes.routes[IO](server, sessionManager, true)
  * val app = Router("/mcp" -> mcpRoutes).orNotFound
  * }}}
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
    *   HTTP routes to mount via Router
    */
  def routes[F[_]: Async](
      server: McpServer[F],
      sessionManager: SessionManager[F],
      enableSessions: Boolean
  ): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {

      case req @ POST -> Root =>
        extractHeaders(req) match {
          case Left(error) =>
            jsonRpcError[F](None, McpError.invalidRequest(error))

          case Right(headers) =>
            val sessionId = if enableSessions then headers.sessionId else None
            sessionId match {
              case Some(id) =>
                sessionManager.getSession(Some(id)).flatMap {
                  case Some(_) =>
                    for {
                      response <- validateProtocolVersion(headers) match {
                        case Some(error) =>
                          jsonRpcError[F](None, McpError.invalidRequest(error))
                        case None =>
                          sessionManager.updateActivity(Some(id)) >>
                            handlePostMessage(req, Some(id), sessionManager)
                      }
                    } yield response
                  case None =>
                    jsonRpcError[F](None, McpError.invalidRequest("Session expired or invalid"))
                }

              case None =>
                // No session - must be initialize or sessionless mode
                handleInitializeOrSessionless(req, server, sessionManager, enableSessions)
            }

        }

      case req @ GET -> Root =>
        extractHeaders(req) match {
          case Left(error) =>
            jsonRpcError[F](None, McpError.invalidRequest(error))

          case Right(headers) =>
            val sessionId = if enableSessions then headers.sessionId else None
            sessionManager.getSession(sessionId).flatMap {
              case Some(_) =>
                for {
                  response <- validateProtocolVersion(headers) match {
                    case Some(error) =>
                      jsonRpcError[F](None, McpError.invalidRequest(error))
                    case None =>
                      createPersistentJsonStream(sessionId, headers.lastEventId, sessionManager)
                  }
                } yield response
              case None =>
                // Auto-create session if no session ID provided (for SSE-only mode)
                if sessionId.isEmpty && enableSessions then
                  for {
                    newSessionId <- sessionManager.createSession(enableSessions, server)
                    response <- createPersistentJsonStream(newSessionId, headers.lastEventId, sessionManager).map { baseResponse =>
                      newSessionId match {
                        case Some(sid) => baseResponse.putHeaders(Header.Raw(ci"Mcp-Session-Id", sid.value))
                        case None      => baseResponse
                      }
                    }
                  } yield response
                else if sessionId.isDefined then jsonRpcError[F](None, McpError.invalidRequest("Session expired"))
                else jsonRpcError[F](None, McpError.invalidRequest("Missing Mcp-Session-Id header"))
            }
        }

      case req @ DELETE -> Root =>
        extractHeaders(req) match {
          case Left(error) =>
            jsonRpcError[F](None, McpError.invalidRequest(error))

          case Right(headers) =>
            headers.sessionId match {
              case Some(sessionId) =>
                sessionManager.removeSession(Some(sessionId)).attempt.flatMap {
                  case Right(_) =>
                    Ok(Json.obj("status" -> Json.fromString("session_terminated")).deepDropNullValues.noSpaces)
                  case Left(error) =>
                    jsonRpcError[F](None, McpError.internalError(s"Failed to remove session: ${error.getMessage}"))
                }
              case None =>
                jsonRpcError[F](None, McpError.invalidRequest("Missing Mcp-Session-Id header"))
            }
        }
    }
  }

  /** Extract and validate HTTP headers. */
  private def extractHeaders[F[_]: Async](req: Request[F]): Either[String, RequestHeaders] = {
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

  /** Validate protocol version header.
    *
    * @param requireVersion
    *   If true (post-initialization), the header is required. If false (initialize request), it's optional.
    */
  private def validateProtocolVersion[F[_]: Async](headers: RequestHeaders, requireVersion: Boolean = true): Option[String] =
    headers.protocolVersion match {
      case Some(version) if version == Constants.LATEST_PROTOCOL_VERSION =>
        None
      case Some(version) =>
        Some(s"Unsupported protocol version: $version. Expected ${Constants.LATEST_PROTOCOL_VERSION}")
      case None if requireVersion =>
        Some(s"Missing required MCP-Protocol-Version header. Expected ${Constants.LATEST_PROTOCOL_VERSION}")
      case None =>
        None
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
      json <- req.as[Json].adaptError(error => new Exception(s"Failed to parse JSON: ${error.getMessage}"))

      // Try parsing as JsonRpcRequest first (client request to server)
      response <- json.as[JsonRpcRequest] match {
        case Right(jsonRpcReq) =>
          // This is a client request to server
          val isNotification = jsonRpcReq match {
            case JsonRpcRequest.Notification(_, _, _) => true
            case JsonRpcRequest.Request(_, _, _, _)   => false
          }

          sessionManager.enqueueRequest(sessionId, jsonRpcReq) >>
            (if isNotification then
               // Notifications get 202 Accepted with empty JSON object
               Accepted(Json.obj().deepDropNullValues.noSpaces, `Content-Type`(MediaType.application.json))
             else
               // Requests need to wait for response from queue
               sessionManager.getSession(sessionId).flatMap {
                 case Some(sessionState) =>
                   val jsonStream = Stream
                     .fromQueueNoneTerminated(sessionState.postResponseQueue)
                     .take(1) // Take single response for this POST request
                     .map(msg => msg.asJson.deepDropNullValues.noSpaces)
                     .through(fs2.text.utf8.encode)

                   Ok(
                     jsonStream,
                     `Content-Type`(MediaType.application.json),
                     `Cache-Control`(CacheDirective.`no-cache`()),
                     Connection(ci"keep-alive")
                   )

                 case None =>
                   Async[F].raiseError(new Exception("Session not found"))
               })

        case Left(_) =>
          // Not a request, try parsing as JsonRpcResponse (client response to server request)
          json.as[JsonRpcResponse] match {
            case Right(jsonRpcResp) =>
              // This is a client response to a server-initiated request
              jsonRpcResp match {
                case JsonRpcResponse.Response(_, id, result) =>
                  // Complete pending server request with success
                  sessionManager.getSession(sessionId).flatMap {
                    case Some(sessionState) =>
                      sessionState.transport.completeRequest(id, Right(result)) >>
                        Accepted(Json.obj().deepDropNullValues.noSpaces, `Content-Type`(MediaType.application.json))
                    case None =>
                      Async[F].raiseError(new Exception("Session not found"))
                  }

                case JsonRpcResponse.Error(_, Some(id), error) =>
                  // Complete pending server request with error
                  sessionManager.getSession(sessionId).flatMap {
                    case Some(sessionState) =>
                      sessionState.transport.completeRequest(id, Left(error)) >>
                        Accepted(Json.obj().deepDropNullValues.noSpaces, `Content-Type`(MediaType.application.json))
                    case None =>
                      Async[F].raiseError(new Exception("Session not found"))
                  }

                case _ =>
                  Async[F].raiseError(new Exception("Invalid JsonRpcResponse format"))
              }

            case Left(error) =>
              // Failed to parse as either request or response
              Async[F].raiseError(new Exception(s"Failed to decode JSON-RPC message: ${error.getMessage}"))
          }
      }
    } yield response

    result.handleErrorWith { error =>
      for {
        requestId <- req.as[Json].attempt.map {
          case Right(json) => json.hcursor.get[RequestId]("id").toOption
          case Left(_)     => None
        }
        response <- jsonRpcError[F](requestId, McpError.internalError(s"Internal error: ${error.getMessage}"))
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
      parsedJson <- req.as[Json].adaptError(error => new Exception(s"Failed to parse JSON: ${error.getMessage}"))
      method <- parsedJson.hcursor.get[String]("method").liftTo[F]
      response <- method match {
        case "initialize" =>
          for {
            jsonRpcReq <- parsedJson.as[JsonRpcRequest].liftTo[F]
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
                  .map(_.asJson.deepDropNullValues.noSpaces)
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
                    baseResponse.map(_.putHeaders(Header.Raw(ci"Mcp-Session-Id", sessionId.value)))
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
        requestId <- req.as[Json].attempt.map {
          case Right(json) => json.hcursor.get[RequestId]("id").toOption
          case Left(_)     => None
        }
        response <- jsonRpcError[F](requestId, McpError.internalError(s"Internal error: ${error.getMessage}"))
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

        val replayStream: Stream[F, (EventId, io.circe.Json)] = lastEventId match {
          case Some(eventId) => Stream.eval(sessionManager.getEventsSince(sessionId, eventId)).flatMap(events => Stream.emits(events))
          case None          => Stream.empty
        }

        val liveStream: Stream[F, (EventId, io.circe.Json)] = Stream
          .fromQueueNoneTerminated(session.persistentQueue)
          .evalMap(msg => sessionManager.appendEvent(sessionId, msg).map(_ -> msg))

        val fullStream = (replayStream ++ liveStream)
          .map { case (eventId, msg) =>
            val data = msg.deepDropNullValues.noSpaces
            s"id: ${eventId.value}\nevent: message\ndata: $data\n\n"
          }
          .through(fs2.text.utf8.encode)

        Ok(
          fullStream,
          `Content-Type`(MediaType.`text/event-stream`),
          `Cache-Control`(CacheDirective.`no-cache`()),
          Connection(ci"keep-alive")
        )

      case None =>
        jsonRpcError[F](None, McpError.invalidRequest("Session not found"))
    }
  }

  private def jsonRpcError[F[_]: Async](
      requestId: Option[RequestId],
      error: ErrorData
  ): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val errorResponse = JsonRpcResponse.Error(
      jsonrpc = Constants.JSONRPC_VERSION,
      id = requestId,
      error = error
    )

    val data = errorResponse.asJson.deepDropNullValues.noSpaces
    Ok(
      Stream(s"event: message\ndata: $data\n\n")
        .covary[F]
        .through(fs2.text.utf8.encode),
      `Content-Type`(MediaType.`text/event-stream`),
      `Cache-Control`(CacheDirective.`no-cache`()),
      Connection(ci"keep-alive")
    )
  }
}
