package mcp.server

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import cats.effect.syntax.spawn.*
import fs2.Stream
import fs2.io.{stdin, stdout}
import fs2.text
import io.circe.parser.*
import io.circe.syntax.*
import mcp.protocol.JsonRpcMessage

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
  * @param chunkSize
  *   Size of chunks to read from stdin (default 4096 bytes)
  */
class StdioTransport[F[_]: Async] private (
    inQueue: Queue[F, Option[JsonRpcMessage]],
    chunkSize: Int
) extends Transport[F] {

  /** Stream of incoming messages from stdin.
    *
    * Reads lines from stdin, parses them as JSON-RPC messages, and emits them. Invalid JSON or malformed messages are logged to stderr but
    * don't stop the stream.
    */
  def receive: Stream[F, JsonRpcMessage] = {
    Stream.fromQueueNoneTerminated(inQueue)
  }

  /** Send a message to stdout.
    *
    * Serializes the message to JSON and writes it to stdout with a newline.
    */
  def send(message: JsonRpcMessage): F[Unit] = {
    val json = message.asJson.noSpaces
    val bytes = (json + "\n").getBytes("UTF-8")
    Stream
      .chunk(fs2.Chunk.array(bytes))
      .through(stdout[F])
      .compile
      .drain
  }

  /** Close the transport.
    *
    * Signals the end of the input stream by enqueueing None.
    */
  def close: F[Unit] = {
    inQueue.offer(None).void
  }
}

object StdioTransport {

  /** Create a stdio transport.
    *
    * This starts a background fiber that:
    *   - Reads lines from stdin
    *   - Parses them as JSON-RPC messages
    *   - Enqueues valid messages
    *   - Logs errors to stderr for invalid messages
    *
    * @param chunkSize
    *   Size of chunks to read from stdin
    * @return
    *   Resource managing the transport and background fiber
    */
  def apply[F[_]: Async](chunkSize: Int = 4096): cats.effect.Resource[F, StdioTransport[F]] = {
    cats.effect.Resource.eval(Queue.unbounded[F, Option[JsonRpcMessage]]).flatMap { queue =>
      // Background fiber to read from stdin and parse messages
      val readLoop = stdin[F](chunkSize)
        .through(text.utf8.decode)
        .through(text.lines)
        .evalMap { line =>
          parse(line).flatMap(_.as[JsonRpcMessage]) match {
            case Right(message) =>
              queue.offer(Some(message))
            case Left(error) =>
              // Log to stderr but don't stop the stream
              Stream
                .emit(s"Failed to parse message: ${error.getMessage}\n")
                .through(text.utf8.encode)
                .through(fs2.io.stderr[F])
                .compile
                .drain
          }
        }
        .onFinalize(queue.offer(None)) // Signal end of stream
        .compile
        .drain

      // Start the read loop in the background
      readLoop.background.map { _ =>
        new StdioTransport[F](queue, chunkSize)
      }
    }
  }
}
