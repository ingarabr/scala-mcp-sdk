package mcp.server

import fs2.Stream
import mcp.protocol.{JsonRpcRequest, JsonRpcResponse}

/** Transport abstraction for MCP communication.
  *
  * A transport handles the low-level message passing between client and server. It provides a stream of incoming messages and a way to send
  * outgoing messages.
  *
  * This is pure infrastructure - it does NOT manage protocol state or lifecycle. That is handled by ConnectionState in the server layer.
  *
  * Implementations include stdio (for command-line tools) and SSE/HTTP (for web-based servers).
  */
trait Transport[F[_]] {

  /** Stream of incoming JSON-RPC requests from the client.
    *
    * The stream should:
    *   - Parse incoming data into JsonRpcRequest objects (Request or Notification)
    *   - Handle malformed messages gracefully (emit errors or skip)
    *   - Complete when the connection is closed
    */
  def receive: Stream[F, JsonRpcRequest]

  /** Send a JSON-RPC response to the client.
    *
    * @param message
    *   The response to send (Response or Error)
    * @return
    *   Effect that completes when the message has been sent
    */
  def send(message: JsonRpcResponse): F[Unit]
}
