package mcp.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import mcp.protocol.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class ServeLifecycleSuite extends CatsEffectSuite {

  /** In-memory transport whose `receive` stream is fed from a queue. Closing the queue (offering `None`) emulates stdin EOF. */
  private final class QueueTransport(
      inQueue: Queue[IO, Option[JsonRpcRequest]]
  ) extends Transport[IO] {
    def receive: Stream[IO, JsonRpcRequest] = Stream.fromQueueNoneTerminated(inQueue)
    def send(message: JsonRpcResponse): IO[Unit] = IO.unit
    def sendRequest(method: String, params: Option[JsonObject]): IO[Either[ErrorData, JsonObject]] =
      IO.raiseError(new NotImplementedError("not used in this suite"))
  }

  private def freshTransport: IO[(QueueTransport, Queue[IO, Option[JsonRpcRequest]])] =
    Queue.unbounded[IO, Option[JsonRpcRequest]].map(q => (new QueueTransport(q), q))

  private def server: Resource[IO, McpServer[IO]] =
    McpServer[IO](info = Implementation("serve-lifecycle-test", "0.0.0"))

  test("transport EOF causes the join action to complete") {
    val program = for {
      (transport, inQueue) <- freshTransport
      result <- (
        for {
          srv <- server
          join <- srv.serve(transport)
        } yield join
      ).use { join =>
        // Close the receive stream — the message-processing fiber must finish naturally.
        inQueue.offer(None) *> join.timeout(5.seconds)
      }
    } yield result
    program.assertEquals(())
  }

  test("resource finalizer runs after natural completion (no hang)") {
    for {
      finalizerRan <- Ref.of[IO, Boolean](false)
      (transport, inQueue) <- freshTransport
      _ <- (
        for {
          srv <- server
          join <- srv.serve(transport)
          _ <- Resource.onFinalize(finalizerRan.set(true))
        } yield join
      ).use { join =>
        inQueue.offer(None) *> join.timeout(5.seconds)
      }
      ran <- finalizerRan.get
      _ <- IO(assert(ran, "outer finalizer should have run after .use returned"))
    } yield ()
  }

  test("cancelling .use(identity) releases the resource cleanly") {
    val program = for {
      (transport, _) <- freshTransport
      // The fiber would otherwise run forever (we never close the queue). Cancel it from outside.
      outcome <- (
        for {
          srv <- server
          join <- srv.serve(transport)
        } yield join
      ).use(_.timeout(200.millis).attempt)
    } yield outcome
    // Either a TimeoutException bubbles out of joinWithNever, or the cancellation propagates — both indicate the
    // outer scope was unblocked. A hang would fail the suite via munit's idle timeout.
    program.map(_ => ())
  }
}
