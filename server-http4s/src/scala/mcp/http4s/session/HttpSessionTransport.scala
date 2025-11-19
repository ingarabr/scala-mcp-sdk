package mcp.http4s.session

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import mcp.protocol.{JsonRpcRequest, JsonRpcResponse}
import mcp.server.Transport

/** HTTP session-aware transport implementation.
  *
  * Bridges HTTP/SSE to the Transport abstraction expected by McpServer. Each session gets its own transport instance.
  */
class HttpSessionTransport[F[_]: Async](
    sessionId: Option[SessionId],
    sessionManager: SessionManager[F],
    requestQueue: Queue[F, Option[JsonRpcRequest]]
) extends Transport[F] {

  /** Receive stream of requests from client.
    *
    * Requests arrive via POST /mcp and are queued here.
    */
  def receive: Stream[F, JsonRpcRequest] =
    Stream.fromQueueNoneTerminated(requestQueue)

  /** Send response (or notification) to client.
    *
    * Per MCP spec: "MUST NOT broadcast the same message across multiple streams"
    *   - Notifications go to persistent GET/SSE stream only
    *   - Responses/Errors go to POST response stream only
    * Both are logged to event log for replay during reconnection.
    */
  def send(message: JsonRpcResponse): F[Unit] =
    message match {
      case m: JsonRpcResponse.Notification =>
        for {
          _ <- sessionManager.enqueuePersistent(sessionId, m)
          _ <- sessionManager.updateActivity(sessionId)
        } yield ()
      case m: (JsonRpcResponse.Response | JsonRpcResponse.Error) =>
        for {
          _ <- sessionManager.enqueuePostResponse(sessionId, m)
          _ <- sessionManager.logEvent(sessionId, m) // Log for replay, but don't send to SSE stream
          _ <- sessionManager.updateActivity(sessionId)
        } yield ()
    }
}

object HttpSessionTransport {

  /** Create a new HTTP session transport. */
  def apply[F[_]: Async](
      sessionId: Option[SessionId],
      sessionManager: SessionManager[F],
      queueSize: Int = 100
  ): F[HttpSessionTransport[F]] =
    Queue.bounded[F, Option[JsonRpcRequest]](queueSize).map { requestQueue =>
      new HttpSessionTransport[F](sessionId, sessionManager, requestQueue)
    }
}
