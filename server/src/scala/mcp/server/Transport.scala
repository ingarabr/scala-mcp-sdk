package mcp.server

import fs2.Stream
import mcp.protocol.{JsonRpcRequest, JsonRpcResponse}

/** Transport abstraction for MCP communication.
  *
  * Handles low-level message passing between client and server. Provides a stream of incoming messages and a way to send outgoing messages.
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
}
