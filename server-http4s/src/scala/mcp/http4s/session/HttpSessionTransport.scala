package mcp.http4s.session

import cats.effect.{Async, Deferred, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import mcp.protocol.{Constants, ErrorData, JsonRpcRequest, JsonRpcResponse, RequestId}
import mcp.server.Transport

import java.util.UUID
import scala.concurrent.duration.*

/** HTTP session-aware transport implementation.
  *
  * Bridges HTTP/SSE to the Transport abstraction expected by McpServer. Each session gets its own transport instance.
  *
  * Supports bidirectional communication:
  *   - Client→Server requests: via POST /mcp * - Server→Client responses: via POST /mcp response body
  *   - Server→Client requests: via SSE stream
  *   - Client→Server responses: via POST /mcp (handled separately from client requests)
  */
class HttpSessionTransport[F[_]: Async](
    sessionId: Option[SessionId],
    sessionManager: SessionManager[F],
    requestQueue: Queue[F, Option[JsonRpcRequest]],
    pendingRequests: Ref[F, Map[RequestId, Deferred[F, Either[ErrorData, JsonObject]]]]
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
        sessionManager.enqueuePersistent(sessionId, m.json) >>
          sessionManager.updateActivity(sessionId)
      case m: (JsonRpcResponse.Response | JsonRpcResponse.Error) =>
        sessionManager.enqueuePostResponse(sessionId, m) >>
          sessionManager.logEvent(sessionId, m.json) >> // Log for replay, but don't send to SSE stream
          sessionManager.updateActivity(sessionId)
    }

  /** Send a request to the client and wait for response.
    *
    * Server-to-client requests are sent via SSE stream. Client responds via POST /mcp.
    */
  def sendRequest(method: String, params: Option[JsonObject] = None): F[Either[ErrorData, JsonObject]] =
    Transport
      .sendRequestWithCorrelation(
        method,
        params,
        pendingRequests,
        requestId => sessionManager.sendServerRequest(sessionId, requestId, method, params)
      )

  /** Internal method to complete a pending request when response arrives.
    *
    * This should be called by the HTTP handler when it receives a response to a server-initiated request.
    */
  def completeRequest(id: RequestId, result: Either[ErrorData, JsonObject]): F[Unit] =
    pendingRequests.get.flatMap { pending =>
      pending.get(id) match {
        case Some(deferred) => deferred.complete(result).void
        case None           => Async[F].unit // No pending request for this ID
      }
    }
}

object HttpSessionTransport {

  /** Create a new HTTP session transport.
    *
    * @param sessionId
    *   Session ID (None for sessionless mode)
    * @param sessionManager
    *   Session manager for accessing session state
    * @param requestQueue
    *   Queue for receiving requests from client (must be the same queue stored in SessionState)
    */
  def apply[F[_]: Async](
      sessionId: Option[SessionId],
      sessionManager: SessionManager[F],
      requestQueue: Queue[F, Option[JsonRpcRequest]]
  ): F[HttpSessionTransport[F]] =
    for {
      pendingRequests <- Ref.of[F, Map[RequestId, Deferred[F, Either[ErrorData, JsonObject]]]](Map.empty)
    } yield new HttpSessionTransport[F](sessionId, sessionManager, requestQueue, pendingRequests)
}
