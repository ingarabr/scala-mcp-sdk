package mcp.server

import cats.effect.{Async, Deferred, Ref, Sync}
import cats.effect.syntax.temporal.*
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import mcp.protocol.{ErrorData, JsonRpcRequest, JsonRpcResponse, McpError, RequestId}

import java.util.UUID
import scala.concurrent.duration.*

/** Transport abstraction for MCP communication.
  *
  * Handles low-level message passing between client and server. Provides a stream of incoming messages and a way to send outgoing messages.
  *
  * Supports bidirectional communication where both client and server can initiate requests.
  *
  * Implementations include stdio (for command-line tools) and SSE/HTTP (for web-based servers).
  */
trait Transport[F[_]] {

  /** Stream of incoming JSON-RPC requests from the client. */
  def receive: Stream[F, JsonRpcRequest]

  /** Send a JSON-RPC response to the client.
    *
    * @param message
    *   The response to send
    */
  def send(message: JsonRpcResponse): F[Unit]

  /** Send a request to the client and wait for the response.
    *
    * This enables server-to-client requests, such as roots/list, sampling/createMessage, etc.
    *
    * @param method
    *   The JSON-RPC method name
    * @param params
    *   Optional parameters for the request
    * @return
    *   Either an error or the result from the client
    */
  def sendRequest(method: String, params: Option[JsonObject] = None): F[Either[ErrorData, JsonObject]]
}

object Transport {

  /** Helper to implement sendRequest with request/response correlation.
    *
    * Handles the common logic of generating IDs, managing pending requests, timeout, and cleanup. Transport implementations only need to
    * provide the actual send operation.
    *
    * @param method
    *   The JSON-RPC method name
    * @param params
    *   Optional parameters for the request
    * @param pendingRequests
    *   Ref tracking pending server-initiated requests
    * @param doSend
    *   Transport-specific send operation, receives the generated request ID
    * @return
    *   Either an error or the result from the client
    */
  def sendRequestWithCorrelation[F[_]: Async](
      method: String,
      params: Option[JsonObject],
      pendingRequests: Ref[F, Map[RequestId, Deferred[F, Either[ErrorData, JsonObject]]]],
      doSend: RequestId => F[Unit]
  ): F[Either[ErrorData, JsonObject]] =
    for {
      requestId <- Sync[F].delay(RequestId(UUID.randomUUID().toString))
      deferred <- Deferred[F, Either[ErrorData, JsonObject]]
      _ <- pendingRequests.update(_ + (requestId -> deferred))
      _ <- doSend(requestId)
      result <- deferred.get.timeout(30.seconds).handleErrorWith { _ =>
        pendingRequests.update(_ - requestId) *>
          Async[F].pure(Left(McpError.internalError("Request timeout or error")))
      }
      _ <- pendingRequests.update(_ - requestId)
    } yield result
}
