package mcp.server

import cats.effect.*
import fs2.Stream
import mcp.protocol.JsonRpcMessage

/** Transport abstraction for MCP communication.
  *
  * A transport handles the low-level message passing between client and server. It provides a stream of incoming messages and a way to send
  * outgoing messages.
  *
  * Implementations include stdio (for command-line tools) and SSE/HTTP (for web-based servers).
  */
trait Transport[F[_]] {

  /** Stream of incoming JSON-RPC messages from the client.
    *
    * The stream should:
    *   - Parse incoming data into JsonRpcMessage objects
    *   - Handle malformed messages gracefully (emit errors or skip)
    *   - Complete when the connection is closed
    */
  def receive: Stream[F, JsonRpcMessage]

  /** Send a JSON-RPC message to the client.
    *
    * @param message
    *   The message to send
    * @return
    *   Effect that completes when the message has been sent
    */
  def send(message: JsonRpcMessage): F[Unit]

  /** Close the transport and clean up resources.
    *
    * This should:
    *   - Close any open connections/streams
    *   - Flush pending messages
    *   - Release acquired resources
    */
  def close: F[Unit]
}

object Transport {

  /** Create a transport as a resource that automatically closes.
    *
    * @param makeTransport
    *   Function to create the transport
    * @return
    *   Resource that manages the transport lifecycle
    */
  def resource[F[_]: Sync](makeTransport: F[Transport[F]]): cats.effect.Resource[F, Transport[F]] = {
    cats.effect.Resource.make(makeTransport)(_.close)
  }
}
