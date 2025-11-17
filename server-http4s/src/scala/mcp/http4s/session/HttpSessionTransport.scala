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

  /** Send response to client.
    *
    * Responses go to both POST response stream and persistent GET stream.
    */
  def send(message: JsonRpcResponse): F[Unit] =
    for {
      _ <- sessionManager.enqueuePostResponse(sessionId, ServerMessage.Response(message))
      _ <- sessionManager.enqueuePersistent(sessionId, ServerMessage.Response(message))
      _ <- sessionManager.updateActivity(sessionId)
    } yield ()
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
