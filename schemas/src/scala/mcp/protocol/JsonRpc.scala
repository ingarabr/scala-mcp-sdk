package mcp.protocol

import io.circe.*
import io.circe.syntax.*
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

/** Messages from client to server (requests and notifications). */
enum JsonRpcRequest {
  case Request(jsonrpc: String, id: RequestId, method: String, params: Option[JsonObject])
  case Notification(jsonrpc: String, method: String, params: Option[JsonObject])
}

object JsonRpcRequest {
  given Codec[JsonRpcRequest] = new Codec[JsonRpcRequest] {
    def apply(c: HCursor): Decoder.Result[JsonRpcRequest] =
      // Distinguish Request vs Notification by presence of id
      for {
        jsonrpc <- c.get[String]("jsonrpc")
        method <- c.get[String]("method")
        params <- c.get[Option[JsonObject]]("params")
        maybeId <- c.get[Option[RequestId]]("id")
        result <- maybeId match {
          case Some(id) => Right(Request(jsonrpc, id, method, params))
          case None     => Right(Notification(jsonrpc, method, params))
        }
      } yield result

    def apply(a: JsonRpcRequest): Json = a match {
      case Request(jsonrpc, id, method, params) =>
        JsonObject(
          "jsonrpc" -> Json.fromString(jsonrpc),
          "id" -> id.asJson,
          "method" -> Json.fromString(method),
          "params" -> params.asJson
        ).asJson
      case Notification(jsonrpc, method, params) =>
        JsonObject(
          "jsonrpc" -> Json.fromString(jsonrpc),
          "method" -> Json.fromString(method),
          "params" -> params.asJson
        ).asJson
    }
  }
}

/** Messages from server to client (responses and errors). */
enum JsonRpcResponse {
  case Response(jsonrpc: String, id: RequestId, result: JsonObject)
  case Error(jsonrpc: String, id: Option[RequestId], error: ErrorData)
}

object JsonRpcResponse {
  given Codec[JsonRpcResponse] = new Codec[JsonRpcResponse] {
    def apply(c: HCursor): Decoder.Result[JsonRpcResponse] =
      // Distinguish Response vs Error by presence of result vs error
      for {
        jsonrpc <- c.get[String]("jsonrpc")
        result <- c.get[Option[JsonObject]]("result").flatMap {
          case Some(res) =>
            // This is a Response - id is required
            c.get[RequestId]("id").map(id => Response(jsonrpc, id, res))
          case None =>
            // This is an Error - id may be null (for parse/invalid request errors)
            for {
              maybeId <- c.get[Option[RequestId]]("id")
              errorData <- c.get[ErrorData]("error")
            } yield Error(jsonrpc, maybeId, errorData)
        }
      } yield result

    def apply(a: JsonRpcResponse): Json = a match {
      case Response(jsonrpc, id, result) =>
        JsonObject(
          "jsonrpc" -> Json.fromString(jsonrpc),
          "id" -> id.asJson,
          "result" -> result.asJson
        ).asJson
      case Error(jsonrpc, id, error) =>
        JsonObject(
          "jsonrpc" -> Json.fromString(jsonrpc),
          "id" -> id.asJson,
          "error" -> error.asJson
        ).asJson
    }
  }
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
