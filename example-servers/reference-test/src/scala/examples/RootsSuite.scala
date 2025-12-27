package examples

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import cats.effect.kernel.Ref
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.*
import mcp.server.{McpServer, Transport}
import munit.CatsEffectSuite
import examples.tools.EchoTool

import scala.concurrent.duration.*

class RootsSuite extends CatsEffectSuite {

  def withServer[A](serverResource: Resource[IO, McpServer[IO]], transport: RootsTestTransport)(
      test: (Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]]) => IO[A]
  ): IO[A] =
    (for {
      server <- serverResource
      _ <- server.serve(transport)
    } yield ()).use(_ => test(transport.serverToClient, transport.clientToServer))

  class RootsTestTransport(
      val serverToClient: Queue[IO, Option[JsonRpcResponse]],
      val clientToServer: Queue[IO, Option[JsonRpcRequest]],
      rootsToReturn: Ref[IO, List[Root]],
      rootsRequests: Ref[IO, Int]
  ) extends Transport[IO] {

    def receive: Stream[IO, JsonRpcRequest] =
      Stream.fromQueueNoneTerminated(clientToServer)

    def send(message: JsonRpcResponse): IO[Unit] =
      serverToClient.offer(Some(message)).void

    def sendRequest(method: String, params: Option[JsonObject]): IO[Either[ErrorData, JsonObject]] =
      method match {
        case "roots/list" =>
          rootsRequests.update(_ + 1) *>
            rootsToReturn.get.map { roots =>
              Right(ListRootsResult(roots).asJsonObject)
            }
        case _ =>
          IO.pure(Left(ErrorData(code = -32601, message = s"Method not found: $method")))
      }

    def getRootsRequestCount: IO[Int] = rootsRequests.get
  }

  object RootsTestTransport {
    def create(
        initialRoots: List[Root] = Nil
    ): IO[(RootsTestTransport, Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]], Ref[IO, List[Root]])] =
      for {
        serverToClient <- Queue.unbounded[IO, Option[JsonRpcResponse]]
        clientToServer <- Queue.unbounded[IO, Option[JsonRpcRequest]]
        rootsRef <- Ref.of[IO, List[Root]](initialRoots)
        rootsRequests <- Ref.of[IO, Int](0)
      } yield (new RootsTestTransport(serverToClient, clientToServer, rootsRef, rootsRequests), serverToClient, clientToServer, rootsRef)
  }

  def sendRequest(
      clientToServer: Queue[IO, Option[JsonRpcRequest]],
      serverToClient: Queue[IO, Option[JsonRpcResponse]],
      method: String,
      params: Option[JsonObject] = None
  ): IO[JsonRpcResponse] = {
    val request = JsonRpcRequest.Request(
      jsonrpc = Constants.JSONRPC_VERSION,
      id = RequestId(s"test-${System.nanoTime()}"),
      method = method,
      params = params
    )
    for {
      _ <- clientToServer.offer(Some(request))
      response <- serverToClient.take.flatMap {
        case Some(msg) => IO.pure(msg)
        case None      => IO.raiseError(new Exception("No response from server"))
      }
    } yield response
  }

  def initRequestWithRoots: InitializeRequest = InitializeRequest(
    protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
    capabilities = ClientCapabilities(roots = Some(RootsCapability(listChanged = Some(true)))),
    clientInfo = Implementation("test-client", "1.0.0")
  )

  def initRequestWithoutRoots: InitializeRequest = InitializeRequest(
    protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
    capabilities = ClientCapabilities(),
    clientInfo = Implementation("test-client", "1.0.0")
  )

  def initializedNotification: JsonRpcRequest.Notification = JsonRpcRequest.Notification(
    jsonrpc = Constants.JSONRPC_VERSION,
    method = "notifications/initialized",
    params = None
  )

  def rootsListChangedNotification: JsonRpcRequest.Notification = JsonRpcRequest.Notification(
    jsonrpc = Constants.JSONRPC_VERSION,
    method = "notifications/roots/list_changed",
    params = None
  )

  test("roots are fetched after initialization when client advertises capability") {
    val testRoots = List(Root(uri = "file:///home/user/project", name = Some("My Project")))

    RootsTestTransport.create(testRoots).flatMap { case (transport, serverToClient, clientToServer, _) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        tools = List(EchoTool[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequestWithRoots.asJsonObject))
          _ <- clientToServer.offer(Some(initializedNotification))
          _ <- IO.sleep(50.millis)
          requestCount <- transport.getRootsRequestCount
          _ = assertEquals(requestCount, 1, "Server should have called roots/list once after initialization")
        } yield ()
      }
    }
  }

  test("roots are NOT fetched when client does not advertise capability") {
    RootsTestTransport.create().flatMap { case (transport, serverToClient, clientToServer, _) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        tools = List(EchoTool[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequestWithoutRoots.asJsonObject))
          _ <- clientToServer.offer(Some(initializedNotification))
          _ <- IO.sleep(50.millis)
          requestCount <- transport.getRootsRequestCount
          _ = assertEquals(requestCount, 0, "Server should NOT call roots/list when client doesn't advertise capability")
        } yield ()
      }
    }
  }

  test("roots are re-fetched when client sends list_changed notification") {
    val initialRoots = List(Root(uri = "file:///project1", name = Some("Project 1")))

    RootsTestTransport.create(initialRoots).flatMap { case (transport, serverToClient, clientToServer, rootsRef) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        tools = List(EchoTool[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequestWithRoots.asJsonObject))
          _ <- clientToServer.offer(Some(initializedNotification))
          _ <- IO.sleep(50.millis)
          count1 <- transport.getRootsRequestCount
          _ = assertEquals(count1, 1, "Should have fetched roots once after init")

          // Update roots and send notification
          _ <- rootsRef.set(List(Root(uri = "file:///project2", name = Some("Project 2"))))
          _ <- clientToServer.offer(Some(rootsListChangedNotification))
          _ <- IO.sleep(50.millis)
          count2 <- transport.getRootsRequestCount
          _ = assertEquals(count2, 2, "Should have fetched roots again after list_changed")
        } yield ()
      }
    }
  }
}
