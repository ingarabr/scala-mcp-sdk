package mcp.server

import cats.effect.*
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.{stdin, stdout}
import fs2.text
import io.circe.{Json, JsonObject}
import io.circe.parser.*
import io.circe.syntax.*
import mcp.protocol.{Constants, ErrorData, JsonRpcRequest, JsonRpcResponse, RequestId}

/** Transport implementation using standard input/output.
  *
  * Reads newline-delimited JSON from stdin and writes to stdout. Standard transport for command-line MCP servers.
  *
  * Supports bidirectional communication where both client and server can initiate requests.
  */
class StdioTransport[F[_]: Async] private (
    inQueue: Queue[F, Option[JsonRpcRequest]],
    outQueue: Queue[F, Option[StdioTransport.OutgoingMessage]],
    pendingRequests: Ref[F, Map[RequestId, Deferred[F, Either[ErrorData, JsonObject]]]]
) extends Transport[F] {

  def receive: Stream[F, JsonRpcRequest] =
    Stream.fromQueueNoneTerminated(inQueue)

  def send(message: JsonRpcResponse): F[Unit] =
    outQueue.offer(Some(StdioTransport.OutgoingMessage.Response(message)))

  def sendRequest(method: String, params: Option[JsonObject] = None): F[Either[ErrorData, JsonObject]] =
    Transport.sendRequestWithCorrelation(
      method,
      params,
      pendingRequests,
      requestId => outQueue.offer(Some(StdioTransport.OutgoingMessage.Request(requestId, method, params)))
    )
}

object StdioTransport {

  /** ADT for outgoing messages (both responses and server-initiated requests). */
  enum OutgoingMessage {
    case Response(message: JsonRpcResponse)
    case Request(id: RequestId, method: String, params: Option[JsonObject])
  }

  /** Create a stdio transport.
    *
    * @param chunkSize
    *   Size of chunks to read from stdin
    * @param outboundQueueSize
    *   Maximum size of outbound message queue. None for unbounded. Bounded queues provide backpressure.
    */
  def apply[F[_]: Async](
      chunkSize: Int = 4096,
      outboundQueueSize: Option[Int] = None
  ): cats.effect.Resource[F, StdioTransport[F]] =
    for {
      inQueue <- cats.effect.Resource.eval(Queue.unbounded[F, Option[JsonRpcRequest]])
      outQueue <- cats.effect.Resource.eval(
        outboundQueueSize match {
          case Some(size) => Queue.bounded[F, Option[OutgoingMessage]](size)
          case None       => Queue.unbounded[F, Option[OutgoingMessage]]
        }
      )
      pendingRequests <- cats.effect.Resource.eval(
        Ref.of[F, Map[RequestId, Deferred[F, Either[ErrorData, JsonObject]]]](Map.empty)
      )
      transport = new StdioTransport[F](inQueue, outQueue, pendingRequests)

      _ <- stdin[F](chunkSize)
        .through(text.utf8.decode)
        .through(text.lines)
        .evalMap { line =>
          parse(line) match {
            case Right(json) =>
              // Try to parse as JsonRpcRequest first (client requests/notifications)
              json.as[JsonRpcRequest] match {
                case Right(request) =>
                  // Client sent us a request/notification
                  inQueue.offer(Some(request))

                case Left(_) =>
                  def updatePending(id: RequestId, value: Either[ErrorData, JsonObject]): F[Unit] =
                    pendingRequests.get.flatMap { pending =>
                      pending.get(id) match {
                        case Some(deferred) => deferred.complete(value).void
                        case None           => Async[F].unit // No pending request for this ID
                      }
                    }

                  json.as[JsonRpcResponse] match {
                    case Right(response) =>
                      response match {
                        case JsonRpcResponse.Response(_, id, result) =>
                          updatePending(id, Right(result))

                        case JsonRpcResponse.Error(_, id, error) =>
                          id.fold(Async[F].unit)(updatePending(_, Left(error)))

                        case JsonRpcResponse.Notification(_, method, params) =>
                          // This is a notification from client to server, treat as request
                          inQueue.offer(Some(JsonRpcRequest.Notification(Constants.JSONRPC_VERSION, method, params)))
                      }

                    case Left(decodeError) =>
                      // Failed to parse as either request or response
                      transport.send(
                        JsonRpcResponse.Error(
                          jsonrpc = Constants.JSONRPC_VERSION,
                          id = None,
                          error = ErrorData(
                            code = Constants.INVALID_REQUEST,
                            message = "Invalid Request",
                            data = Some(Json.fromString(decodeError.getMessage))
                          )
                        )
                      )
                  }
              }

            case Left(parseError) =>
              transport.send(
                JsonRpcResponse.Error(
                  jsonrpc = Constants.JSONRPC_VERSION,
                  id = None,
                  error = ErrorData(
                    code = Constants.PARSE_ERROR,
                    message = "Parse error",
                    data = Some(Json.fromString(parseError.getMessage))
                  )
                )
              )
          }
        }
        .onFinalize(inQueue.offer(None))
        .compile
        .drain
        .background

      _ <- Stream
        .fromQueueNoneTerminated(outQueue)
        .evalMap { outgoing =>
          val jsonToSend = outgoing match {
            case OutgoingMessage.Response(message)           => message.asJson
            case OutgoingMessage.Request(id, method, params) =>
              JsonObject(
                "jsonrpc" -> Json.fromString(Constants.JSONRPC_VERSION),
                "id" -> id.asJson,
                "method" -> Json.fromString(method),
                "params" -> params.asJson
              ).asJson
          }
          val json = jsonToSend.deepDropNullValues.noSpaces ++ "\n"
          val bytes = json.getBytes("UTF-8")
          Stream.chunk(fs2.Chunk.array(bytes)).through(stdout[F]).compile.drain
        }
        .compile
        .drain
        .background

      _ <- cats.effect.Resource.onFinalize(outQueue.offer(None))
    } yield transport
}
