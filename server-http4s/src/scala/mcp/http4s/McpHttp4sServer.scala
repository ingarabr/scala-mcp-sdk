package mcp.http4s

import cats.effect.{Async, Ref, Resource as CatsResource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{Constants, ErrorData, JsonRpcRequest, JsonRpcResponse}
import mcp.server.Transport
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** Server-Sent Events (SSE) transport for MCP over HTTP.
  *
  * This transport implements the MCP SSE protocol:
  *   - Server pushes messages to client via GET /sse endpoint (Server-Sent Events)
  *   - Client sends messages to server via POST /message endpoint
  *
  * The transport provides HTTP routes that can be integrated into any http4s server. Users are responsible for:
  *   - Creating and managing the HTTP server (e.g., Ember, Blaze)
  *   - Adding middleware (CORS, logging, metrics, etc.)
  *   - Server lifecycle management
  *
  * Example usage:
  * {{{
  * import org.http4s.ember.server.EmberServerBuilder
  * import org.http4s.server.middleware.CORS
  *
  * SSETransport[IO]().use { transport =>
  *   val routes = CORS.policy.withAllowOriginAll(transport.routes)
  *
  *   EmberServerBuilder[IO]
  *     .withHost("0.0.0.0")
  *     .withPort(3000)
  *     .withHttpApp(routes.orNotFound)
  *     .build
  *     .use { server =>
  *       mcpServer.serve(transport)
  *     }
  * }
  * }}}
  */
object McpHttp4sServer {

  /** Create a new SSE transport.
    *
    * @param queueSize
    *   Size of internal message queues (default: 100)
    * @return
    *   Resource managing the transport lifecycle
    */
  def apply[F[_]: Async](queueSize: Int = 100): CatsResource[F, McpHttp4sServer[F]] =
    for {
      // Queue for requests from client to server (received via POST)
      incomingQueue <- CatsResource.eval(Queue.bounded[F, Option[JsonRpcRequest]](queueSize))
      // Queue for messages from server to client (sent via SSE) - includes responses and notifications
      outgoingQueue <- CatsResource.eval(Queue.bounded[F, Option[JsonRpcResponse]](queueSize))
      // Track active SSE connections
      connectionCount <- CatsResource.eval(Ref.of[F, Int](0))
    } yield new McpHttp4sServer[F](incomingQueue, outgoingQueue, connectionCount)
}

class McpHttp4sServer[F[_]: Async](
    incomingQueue: Queue[F, Option[JsonRpcRequest]],
    outgoingQueue: Queue[F, Option[JsonRpcResponse]],
    connectionCount: Ref[F, Int]
) extends Transport[F] {

  private val dsl = new Http4sDsl[F] {}
  import dsl.*

  /** HTTP routes for the SSE transport.
    *
    * Provides two endpoints:
    *   - GET /sse: Server-Sent Events stream for server-to-client messages
    *   - POST /message: Endpoint for client-to-server messages
    *
    * These routes should be mounted in your http4s server. Example:
    * {{{
    * val httpApp = Router(
    *   "/mcp" -> transport.routes
    * ).orNotFound
    * }}}
    */
  def routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // GET /sse - Server-Sent Events endpoint
    // Client establishes a long-lived connection to receive server messages
    case GET -> Root / "sse" =>
      val stream = Stream
        .fromQueueNoneTerminated(outgoingQueue)
        .map { message =>
          // Serialize message to JSON and format as SSE event
          val data = message.asJson.deepDropNullValues.noSpaces
          s"event: message\ndata: $data\n\n"
        }

      // Track connection
      val streamWithTracking = Stream.eval(connectionCount.update(_ + 1)) >>
        stream.onFinalize(connectionCount.update(_ - 1))

      Ok(
        streamWithTracking,
        `Content-Type`(MediaType.unsafeParse("text/event-stream")),
        Header.Raw(CIString("Cache-Control"), "no-cache"),
        Header.Raw(CIString("Connection"), "keep-alive")
      )

    // POST /message - Client sends a request to the server
    case req @ POST -> Root / "message" =>
      req
        .as[Json]
        .flatMap { json =>
          json.as[JsonRpcRequest] match {
            case Right(request) =>
              incomingQueue.offer(Some(request)) >> Accepted()

            case Left(decodeError) =>
              val errorResponse = JsonRpcResponse.Error(
                jsonrpc = Constants.JSONRPC_VERSION,
                id = None,
                error = ErrorData(
                  code = Constants.INVALID_REQUEST,
                  message = "Invalid Request",
                  data = Some(Json.fromString(decodeError.getMessage))
                )
              )
              outgoingQueue.offer(Some(errorResponse)) >> Accepted()
          }
        }
        .handleErrorWith { parseError =>
          val errorResponse = JsonRpcResponse.Error(
            jsonrpc = Constants.JSONRPC_VERSION,
            id = None,
            error = ErrorData(
              code = Constants.PARSE_ERROR,
              message = "Parse error",
              data = Some(Json.fromString(parseError.getMessage))
            )
          )
          outgoingQueue.offer(Some(errorResponse)) >> Accepted()
        }
  }

  /** Send a JSON-RPC response to the client via SSE stream. */
  def send(message: JsonRpcResponse): F[Unit] =
    outgoingQueue.offer(Some(message)).void

  /** Receive a stream of JSON-RPC requests from the client. */
  def receive: Stream[F, JsonRpcRequest] =
    Stream.fromQueueNoneTerminated(incomingQueue)

  /** Get the current number of active SSE connections. */
  def activeConnections: F[Int] = connectionCount.get
}
