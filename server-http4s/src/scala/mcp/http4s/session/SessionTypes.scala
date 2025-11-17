package mcp.http4s.session

import cats.effect.{Fiber, Sync}
import cats.effect.std.Queue
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import mcp.protocol.{ClientCapabilities, JsonRpcRequest, JsonRpcResponse}

import java.time.Instant
import java.util.UUID

opaque type SessionId = String

object SessionId {

  def generate[F[_]: Sync](): F[SessionId] =
    Sync[F].delay(UUID.randomUUID().toString)

  def fromString(s: String): SessionId = s

  extension (id: SessionId) {
    def value: String = id
  }

  given Encoder[SessionId] = Encoder.encodeString.contramap[SessionId](identity)
  given Decoder[SessionId] = Decoder.decodeString.map[SessionId](identity)
}

opaque type EventId = Long

object EventId {

  def zero: EventId = 0L

  def fromString(s: String): Option[EventId] = s.toLongOption

  extension (id: EventId) {
    def value: Long = id
    def next: EventId = id + 1
  }

  given Encoder[EventId] = Encoder.encodeLong.contramap[EventId](identity)
  given Decoder[EventId] = Decoder.decodeLong.map[EventId](identity)
}

/** Server message types that can be sent via SSE. */
sealed trait ServerMessage

object ServerMessage {

  /** JSON-RPC response to client request. */
  case class Response(msg: JsonRpcResponse) extends ServerMessage

  /** Server-initiated request to client. */
  case class Request(msg: JsonRpcRequest) extends ServerMessage

  /** Server-initiated notification to client. */
  case class Notification(msg: JsonRpcRequest) extends ServerMessage
}

/** State for a single MCP session.
  *
  * @param id
  *   Session identifier (None for sessionless mode)
  * @param capabilities
  *   Client capabilities from initialize
  * @param createdAt
  *   Session creation timestamp
  * @param lastActivity
  *   Last client activity timestamp (for timeout)
  * @param eventLog
  *   Log of SSE events for reconnection support
  * @param nextEventId
  *   Next event ID to assign
  * @param postResponseQueue
  *   Queue for messages sent via POST response streams
  * @param persistentQueue
  *   Queue for messages sent via GET persistent stream
  * @param requestQueue
  *   Queue for incoming requests from POST /mcp
  * @param transport
  *   HTTP transport for this session
  * @param serverFiber
  *   Background fiber running McpServer.serve for this session
  */
case class SessionState[F[_]](
    id: Option[SessionId],
    capabilities: Option[ClientCapabilities],
    createdAt: Instant,
    lastActivity: Instant,
    eventLog: Vector[(EventId, ServerMessage)],
    nextEventId: EventId,
    postResponseQueue: Queue[F, Option[ServerMessage]],
    persistentQueue: Queue[F, Option[ServerMessage]],
    requestQueue: Queue[F, Option[JsonRpcRequest]],
    transport: HttpSessionTransport[F],
    serverFiber: Fiber[F, Throwable, Unit]
)
