package mcp.server

import cats.effect.*
import cats.effect.std.Queue
import cats.effect.syntax.spawn.*
import fs2.Stream
import fs2.io.{stdin, stdout}
import fs2.text
import io.circe.parser.*
import io.circe.syntax.*
import mcp.protocol.{Constants, ErrorData, JsonRpcRequest, JsonRpcResponse}

/** Transport implementation using standard input/output.
  *
  * Reads newline-delimited JSON from stdin and writes to stdout. Standard transport for command-line MCP servers.
  */
class StdioTransport[F[_]: Async] private (
    inQueue: Queue[F, Option[JsonRpcRequest]],
    outQueue: Queue[F, Option[JsonRpcResponse]]
) extends Transport[F] {

  def receive: Stream[F, JsonRpcRequest] =
    Stream.fromQueueNoneTerminated(inQueue)

  def send(message: JsonRpcResponse): F[Unit] =
    outQueue.offer(Some(message))
}

object StdioTransport {

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
          case Some(size) => Queue.bounded[F, Option[JsonRpcResponse]](size)
          case None       => Queue.unbounded[F, Option[JsonRpcResponse]]
        }
      )
      transport = new StdioTransport[F](inQueue, outQueue)

      _ <- stdin[F](chunkSize)
        .through(text.utf8.decode)
        .through(text.lines)
        .evalMap { line =>
          parse(line) match {
            case Right(json) =>
              json.as[JsonRpcRequest] match {
                case Right(request) =>
                  inQueue.offer(Some(request))

                case Left(decodeError) =>
                  transport.send(
                    JsonRpcResponse.Error(
                      jsonrpc = Constants.JSONRPC_VERSION,
                      id = None,
                      error = ErrorData(
                        code = Constants.INVALID_REQUEST,
                        message = "Invalid Request",
                        data = Some(io.circe.Json.fromString(decodeError.getMessage))
                      )
                    )
                  )
              }

            case Left(parseError) =>
              transport.send(
                JsonRpcResponse.Error(
                  jsonrpc = Constants.JSONRPC_VERSION,
                  id = None,
                  error = ErrorData(
                    code = Constants.PARSE_ERROR,
                    message = "Parse error",
                    data = Some(io.circe.Json.fromString(parseError.getMessage))
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
        .evalMap { message =>
          val json = message.asJson.deepDropNullValues.noSpaces ++ "\n"
          val bytes = json.getBytes("UTF-8")
          Stream
            .chunk(fs2.Chunk.array(bytes))
            .through(stdout[F])
            .compile
            .drain
        }
        .compile
        .drain
        .background

      _ <- cats.effect.Resource.onFinalize(outQueue.offer(None))
    } yield transport
}
