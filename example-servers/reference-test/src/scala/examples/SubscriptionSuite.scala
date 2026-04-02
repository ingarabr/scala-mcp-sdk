package examples

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.*
import mcp.server.{McpServer, ResourceDef, ResourceUri, Transport}
import munit.CatsEffectSuite
import examples.resources.TimestampResource

import scala.concurrent.duration.*

class SubscriptionSuite extends CatsEffectSuite {

  def withServer[A](serverResource: Resource[IO, McpServer[IO]], transport: TestTransport)(
      test: (Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]]) => IO[A]
  ): IO[A] =
    (for {
      server <- serverResource
      _ <- server.serve(transport)
    } yield ()).use(_ => test(transport.serverToClient, transport.clientToServer))

  /** Test transport that can capture notifications */
  class TestTransport(
      val serverToClient: Queue[IO, Option[JsonRpcResponse]],
      val clientToServer: Queue[IO, Option[JsonRpcRequest]],
      notifications: Ref[IO, List[JsonRpcResponse.Notification]]
  ) extends Transport[IO] {

    def receive: Stream[IO, JsonRpcRequest] =
      Stream.fromQueueNoneTerminated(clientToServer)

    def send(message: JsonRpcResponse): IO[Unit] =
      message match {
        case n: JsonRpcResponse.Notification =>
          notifications.update(_ :+ n) *> serverToClient.offer(Some(message)).void
        case _ =>
          serverToClient.offer(Some(message)).void
      }

    def sendRequest(method: String, params: Option[JsonObject]): IO[Either[ErrorData, JsonObject]] =
      IO.raiseError(new NotImplementedError("TestTransport.sendRequest not implemented"))

    def getNotifications: IO[List[JsonRpcResponse.Notification]] =
      notifications.get
  }

  object TestTransport {
    def create: IO[(TestTransport, Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]])] =
      for {
        serverToClient <- Queue.unbounded[IO, Option[JsonRpcResponse]]
        clientToServer <- Queue.unbounded[IO, Option[JsonRpcRequest]]
        notifications <- Ref.of[IO, List[JsonRpcResponse.Notification]](Nil)
      } yield (new TestTransport(serverToClient, clientToServer, notifications), serverToClient, clientToServer)
  }

  /** Create a test resource with a controllable updates stream */
  def testResource(updatesTopic: Topic[IO, Unit]): ResourceDef[IO, String] =
    ResourceDef[IO, String](
      uri = "test://resource",
      name = "Test Resource",
      handler = _ => IO.pure(Some("test content")),
      updates = updatesTopic.subscribe(100).void
    )

  /** Helper to send a request and get a response */
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

  def initializeServer(
      clientToServer: Queue[IO, Option[JsonRpcRequest]],
      serverToClient: Queue[IO, Option[JsonRpcResponse]]
  ): IO[Unit] = {
    val initRequest = InitializeRequest(
      protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
      capabilities = ClientCapabilities(),
      clientInfo = Implementation("test-client", "1.0.0")
    )
    for {
      _ <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequest.asJsonObject))
      _ <- clientToServer.offer(
        Some(
          JsonRpcRequest.Notification(
            jsonrpc = Constants.JSONRPC_VERSION,
            method = "notifications/initialized",
            params = None
          )
        )
      )
    } yield ()
  }

  test("subscribe to static resource succeeds") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        resources = List(TimestampResource[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- initializeServer(clientToServer, serverToClient)

          subscribeRequest = SubscribeRequest(uri = TimestampResource.uri)
          response <- sendRequest(clientToServer, serverToClient, "resources/subscribe", Some(subscribeRequest.asJsonObject))

          _ = response match {
            case JsonRpcResponse.Response(_, _, _) =>
              // Success - subscribe returns EmptyResult
              ()
            case JsonRpcResponse.Error(_, _, error) =>
              fail(s"Subscribe should succeed, got error: ${error.message}")
            case other =>
              fail(s"Unexpected response: $other")
          }
        } yield ()
      }
    }
  }

  test("subscribe to non-existent resource fails") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        resources = List(TimestampResource[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- initializeServer(clientToServer, serverToClient)

          // Try to subscribe to a non-existent resource
          subscribeRequest = SubscribeRequest(uri = "nonexistent://resource")
          response <- sendRequest(clientToServer, serverToClient, "resources/subscribe", Some(subscribeRequest.asJsonObject))

          _ = response match {
            case JsonRpcResponse.Error(_, _, error) =>
              assert(error.message.contains("Resource not found"), s"Error should mention resource not found: ${error.message}")
            case JsonRpcResponse.Response(_, _, _) =>
              fail("Subscribe to non-existent resource should fail")
            case other =>
              fail(s"Unexpected response: $other")
          }
        } yield ()
      }
    }
  }

  test("unsubscribe from resource succeeds") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        resources = List(TimestampResource[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- initializeServer(clientToServer, serverToClient)

          // Subscribe first
          subscribeRequest = SubscribeRequest(uri = TimestampResource.uri)
          _ <- sendRequest(clientToServer, serverToClient, "resources/subscribe", Some(subscribeRequest.asJsonObject))

          // Then unsubscribe
          unsubscribeRequest = UnsubscribeRequest(uri = TimestampResource.uri)
          response <- sendRequest(clientToServer, serverToClient, "resources/unsubscribe", Some(unsubscribeRequest.asJsonObject))

          _ = response match {
            case JsonRpcResponse.Response(_, _, _) =>
              // Success - unsubscribe returns EmptyResult
              ()
            case JsonRpcResponse.Error(_, _, error) =>
              fail(s"Unsubscribe should succeed, got error: ${error.message}")
            case other =>
              fail(s"Unexpected response: $other")
          }
        } yield ()
      }
    }
  }

  test("unsubscribe is idempotent - succeeds even when not subscribed") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        resources = List(TimestampResource[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        for {
          _ <- initializeServer(clientToServer, serverToClient)

          // Unsubscribe without subscribing first - should still succeed
          unsubscribeRequest = UnsubscribeRequest(uri = TimestampResource.uri)
          response <- sendRequest(clientToServer, serverToClient, "resources/unsubscribe", Some(unsubscribeRequest.asJsonObject))

          _ = response match {
            case JsonRpcResponse.Response(_, _, _) =>
              ()
            case JsonRpcResponse.Error(_, _, error) =>
              fail(s"Unsubscribe should be idempotent, got error: ${error.message}")
            case other =>
              fail(s"Unexpected response: $other")
          }
        } yield ()
      }
    }
  }

  test("resource updates stream triggers notifications for subscribed resources") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      Topic[IO, Unit].flatMap { updatesTopic =>
        val resource = testResource(updatesTopic)
        val serverResource = McpServer[IO](
          info = Implementation("test-server", "1.0.0"),
          resources = List(resource)
        )

        withServer(serverResource, transport) { (serverToClient, clientToServer) =>
          for {
            _ <- initializeServer(clientToServer, serverToClient)

            // Subscribe to the test resource
            subscribeRequest = SubscribeRequest(uri = "test://resource")
            _ <- sendRequest(clientToServer, serverToClient, "resources/subscribe", Some(subscribeRequest.asJsonObject))

            // Wait for the updates stream to start subscribing to the topic
            _ <- IO.sleep(50.millis)

            // Trigger an update via the topic
            _ <- updatesTopic.publish1(())

            // Wait a bit for the notification to be processed
            _ <- IO.sleep(100.millis)

            // Check that a notification was sent
            notifications <- transport.getNotifications

            _ = assert(
              notifications.exists(n => n.method == "notifications/resources/updated"),
              s"Should have received resource updated notification, got: $notifications"
            )
          } yield ()
        }
      }
    }
  }

  test("notifications are NOT sent for non-subscribed resources") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      Topic[IO, Unit].flatMap { updatesTopic =>
        val resource = testResource(updatesTopic)
        val serverResource = McpServer[IO](
          info = Implementation("test-server", "1.0.0"),
          resources = List(resource)
        )

        withServer(serverResource, transport) { (serverToClient, clientToServer) =>
          for {
            _ <- initializeServer(clientToServer, serverToClient)

            // DON'T subscribe - just trigger an update
            _ <- updatesTopic.publish1(())

            // Wait a bit
            _ <- IO.sleep(100.millis)

            // Check that NO notification was sent
            notifications <- transport.getNotifications

            _ = assert(
              !notifications.exists(n => n.method == "notifications/resources/updated"),
              s"Should NOT have received notification when not subscribed, got: $notifications"
            )
          } yield ()
        }
      }
    }
  }

  test("capabilities advertise subscription support") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        resources = List(TimestampResource[IO])
      )

      withServer(serverResource, transport) { (serverToClient, clientToServer) =>
        val initRequest = InitializeRequest(
          protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
          capabilities = ClientCapabilities(),
          clientInfo = Implementation("test-client", "1.0.0")
        )
        for {
          response <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequest.asJsonObject))

          _ = response match {
            case JsonRpcResponse.Response(_, _, result) =>
              result.asJson.as[InitializeResult].toOption match {
                case Some(initResult) =>
                  initResult.capabilities.resources match {
                    case Some(resourcesCap) =>
                      assertEquals(resourcesCap.subscribe, Some(true), "Resources should advertise subscribe=true")
                      assertEquals(resourcesCap.listChanged, Some(true), "Resources should advertise listChanged=true")
                    case None =>
                      fail("Server should have resources capability")
                  }
                case None =>
                  fail(s"Failed to decode InitializeResult: $result")
              }
            case other =>
              fail(s"Expected Response, got: $other")
          }
        } yield ()
      }
    }
  }

  test("notifyResourceUpdated API sends notification to subscribed clients") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      val serverResource = McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        resources = List(TimestampResource[IO])
      )

      (for {
        server <- serverResource
        _ <- server.serve(transport)
      } yield server).use { server =>
        for {
          _ <- initializeServer(clientToServer, serverToClient)

          // Subscribe to the timestamp resource
          subscribeRequest = SubscribeRequest(uri = TimestampResource.uri)
          _ <- sendRequest(clientToServer, serverToClient, "resources/subscribe", Some(subscribeRequest.asJsonObject))

          // Use the notification API directly
          _ <- server.notifyResourceUpdated(ResourceUri(TimestampResource.uri))

          // Wait for notification
          _ <- IO.sleep(50.millis)

          // Check notification was received
          notifications <- transport.getNotifications

          _ = assert(
            notifications.exists { n =>
              n.method == "notifications/resources/updated" &&
              n.params.exists(_.asJson.hcursor.get[String]("uri").contains(TimestampResource.uri))
            },
            s"Should have received notification for ${TimestampResource.uri}, got: $notifications"
          )
        } yield ()
      }
    }
  }
}
