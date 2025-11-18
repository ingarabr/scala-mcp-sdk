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
    * Responses and errors go to both POST response stream and persistent GET stream. Notifications only go to persistent GET stream (not
    * POST response).
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
          _ <- sessionManager.enqueuePersistent(sessionId, m)
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
