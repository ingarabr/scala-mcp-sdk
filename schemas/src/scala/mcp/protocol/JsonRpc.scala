package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

def stringOrIntCodec[A](de: String | Int => A, en: A => String | Int): Codec[A] =

  Codec.from[A](
    Decoder.decodeInt.map(de).or(Decoder.decodeString.map(de)),
    Encoder.instance[A](v =>
      en(v) match {
        case s: String => Encoder.encodeString(s)
        case i: Int    => Encoder.encodeInt(i)
      }
    )
  )

/** A uniquely identifying ID for a request in JSON-RPC. */
//type RequestId = String | Int
case class RequestId(value: String | Int)

case object RequestId {
  given Codec[RequestId] = stringOrIntCodec(RequestId.apply, _.value)
}

/** A progress token, used to associate progress notifications with the original request. */
case class ProgressToken(value: String | Int)

case object ProgressToken {
  given Codec[ProgressToken] = stringOrIntCodec(ProgressToken.apply, _.value)
}

/** An opaque token used to represent a cursor for pagination. */
type Cursor = String

object Constants {
  val JSONRPC_VERSION = "2.0"
  val LATEST_PROTOCOL_VERSION = "2025-03-26"

  // Standard JSON-RPC error codes
  val PARSE_ERROR = -32700
  val INVALID_REQUEST = -32600
  val METHOD_NOT_FOUND = -32601
  val INVALID_PARAMS = -32602
  val INTERNAL_ERROR = -32603
}

/** Refers to any valid JSON-RPC object that can be decoded off the wire, or encoded to be sent.
  */
enum JsonRpcMessage {
  case Request(jsonrpc: String, id: RequestId, method: String, params: Option[JsonObject])
  case Notification(jsonrpc: String, method: String, params: Option[JsonObject])
  case Response(jsonrpc: String, id: RequestId, result: JsonObject)
  case Error(jsonrpc: String, id: RequestId, error: ErrorData)

  case BatchRequest(messages: List[JsonRpcMessage])
  case BatchResponse(messages: List[JsonRpcMessage])
}

object JsonRpcMessage {
  given Codec[JsonRpcMessage] = Codec.AsObject.derived[JsonRpcMessage]
}

/** Error data in a JSON-RPC error response.
  */
case class ErrorData(
    /** The error type that occurred. */
    code: Int,

    /** A short description of the error. The message SHOULD be limited to a concise single sentence. */
    message: String,

    /** Additional information about the error. The value of this member is defined by the sender (e.g. detailed error information, nested
      * errors etc.).
      */
    data: Option[Json] = None
) derives Codec.AsObject

/** A response that indicates success but carries no data.
  */
case class EmptyResult(
    _meta: Option[JsonObject] = None
) derives Codec.AsObject
