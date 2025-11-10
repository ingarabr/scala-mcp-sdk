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
  * This transport reads newline-delimited JSON from stdin and writes to stdout. It's the standard transport for command-line MCP servers.
  *
  * Messages are:
  *   - Read from stdin, one JSON object per line
  *   - Written to stdout, one JSON object per line
  *   - Encoded/decoded using Circe
  *
  * @param inQueue
  *   Queue for incoming messages parsed from stdin
  */
class StdioTransport[F[_]: Async] private (
    inQueue: Queue[F, Option[JsonRpcRequest]]
) extends Transport[F] {

  /** Stream of incoming requests from stdin.
    *
    * Reads lines from stdin, parses them as JSON-RPC requests, and emits them. Invalid JSON or malformed messages are logged to stderr but
    * don't stop the stream.
    */
  def receive: Stream[F, JsonRpcRequest] =
    Stream.fromQueueNoneTerminated(inQueue)

  /** Send a response to stdout.
    *
    * Serializes the response to JSON and writes it to stdout with a newline.
    */
  def send(message: JsonRpcResponse): F[Unit] = {
    val json = message.asJson.deepDropNullValues.noSpaces ++ "\n"
    val bytes = json.getBytes("UTF-8")
    Stream
      .chunk(fs2.Chunk.array(bytes))
      .through(stdout[F])
      .compile
      .drain
  }
}

object StdioTransport {

  /** Create a stdio transport.
    *
    * This starts a background fiber that:
    *   - Reads lines from stdin
    *   - Parses them as JSON-RPC requests
    *   - Enqueues valid requests
    *   - Sends JSON-RPC error responses for invalid messages (per spec)
    *
    * @param chunkSize
    *   Size of chunks to read from stdin
    * @return
    *   Resource managing the transport and background fiber
    */
  def apply[F[_]: Async](chunkSize: Int = 4096): cats.effect.Resource[F, StdioTransport[F]] =
    for {
      queue <- cats.effect.Resource.eval(Queue.unbounded[F, Option[JsonRpcRequest]])
      transport = new StdioTransport[F](queue)

      // Background fiber to read from stdin and parse messages
      _ <- stdin[F](chunkSize)
        .through(text.utf8.decode)
        .through(text.lines)
        .evalMap { line =>
          parse(line) match {
            case Right(json) =>
              json.as[JsonRpcRequest] match {
                case Right(request) =>
                  queue.offer(Some(request))

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
                  id = None, // Per JSON-RPC spec: id MUST be null for parse errors
                  error = ErrorData(
                    code = Constants.PARSE_ERROR,
                    message = "Parse error",
                    data = Some(io.circe.Json.fromString(parseError.getMessage))
                  )
                )
              )
          }
        }
        .onFinalize(queue.offer(None)) // Signal end of stream
        .compile
        .drain
        .background
    } yield transport
}
