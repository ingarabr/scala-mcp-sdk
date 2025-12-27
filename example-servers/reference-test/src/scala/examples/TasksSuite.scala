package examples

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.*
import mcp.server.{McpServer, Transport}
import munit.CatsEffectSuite
import examples.tools.EchoTool

import scala.concurrent.duration.*

class TasksSuite extends CatsEffectSuite {

  def withServer[A](serverResource: Resource[IO, McpServer[IO]])(
      test: (Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]]) => IO[A]
  ): IO[A] =
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      (for {
        server <- serverResource
        _ <- server.serve(transport)
      } yield ()).use(_ => test(serverToClient, clientToServer))
    }

  class TestTransport(
      serverToClient: Queue[IO, Option[JsonRpcResponse]],
      clientToServer: Queue[IO, Option[JsonRpcRequest]]
  ) extends Transport[IO] {

    def receive: Stream[IO, JsonRpcRequest] =
      Stream.fromQueueNoneTerminated(clientToServer)

    def send(message: JsonRpcResponse): IO[Unit] =
      serverToClient.offer(Some(message)).void

    def sendRequest(method: String, params: Option[JsonObject]): IO[Either[ErrorData, JsonObject]] =
      IO.raiseError(new NotImplementedError("TestTransport.sendRequest not implemented"))
  }

  object TestTransport {
    def create: IO[(TestTransport, Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]])] =
      for {
        serverToClient <- Queue.unbounded[IO, Option[JsonRpcResponse]]
        clientToServer <- Queue.unbounded[IO, Option[JsonRpcRequest]]
      } yield (new TestTransport(serverToClient, clientToServer), serverToClient, clientToServer)
  }

  def sendRequest(
      clientToServer: Queue[IO, Option[JsonRpcRequest]],
      serverToClient: Queue[IO, Option[JsonRpcResponse]],
      method: String,
      params: Option[JsonObject] = None,
      requestId: String = "test-1"
  ): IO[JsonRpcResponse] = {
    val request = JsonRpcRequest.Request(
      jsonrpc = Constants.JSONRPC_VERSION,
      id = RequestId(requestId),
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

  test("server with tasks enabled should advertise tasks capability") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(EchoTool[IO]),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val initRequest = InitializeRequest(
        protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
        capabilities = ClientCapabilities(),
        clientInfo = Implementation("test-client", "1.0.0")
      )
      for {
        response <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequest.asJsonObject))
        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val initResult = result.asJson.as[InitializeResult]
            initResult.toOption match {
              case Some(res) =>
                assert(res.capabilities.tasks.isDefined, "Server should advertise tasks capability")
                assert(
                  res.capabilities.tasks.flatMap(_.requests).flatMap(_.tools).flatMap(_.call).isDefined,
                  "Tasks should support tools/call"
                )
              case None =>
                fail(s"Failed to decode InitializeResult from: $result")
            }
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tasks/list should return empty list initially") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(EchoTool[IO]),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tasks/list")
        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val listResult = result.asJson.as[ListTasksResult]
            listResult.toOption match {
              case Some(res) =>
                assertEquals(res.tasks, List.empty[Task])
              case None =>
                fail(s"Failed to decode ListTasksResult from: $result")
            }
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("task-augmented tools/call should return CreateTaskResult") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(EchoTool[IO]),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("echo"),
        "arguments" -> Json.obj("message" -> Json.fromString("Hello!")),
        "task" -> Json.obj("ttl" -> Json.fromLong(60000L))
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest))
        taskId <- IO {
          response match {
            case JsonRpcResponse.Response(_, _, result) =>
              val createResult = result.asJson.as[CreateTaskResult]
              createResult.toOption match {
                case Some(res) =>
                  assertEquals(res.task.status, TaskStatus.working)
                  assert(res.task.taskId.nonEmpty)
                  res.task.taskId
                case None =>
                  fail(s"Failed to decode CreateTaskResult from: $result")
              }
            case other =>
              fail(s"Expected Response, got: $other")
          }
        }

        // Wait for task to complete
        _ <- IO.sleep(100.millis)

        // Poll tasks/get
        getRequest = GetTaskRequest(taskId = taskId)
        getResponse <- sendRequest(clientToServer, serverToClient, "tasks/get", Some(getRequest.asJsonObject), "test-2")
        _ = getResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            val getResult = result.asJson.as[GetTaskResult]
            getResult.toOption match {
              case Some(res) =>
                assertEquals(res.task.status, TaskStatus.completed)
              case None =>
                fail(s"Failed to decode GetTaskResult from: $result")
            }
          case other =>
            fail(s"Expected Response, got: $other")
        }

        // Get the result
        resultRequest = GetTaskResultRequest(taskId = taskId)
        resultResponse <- sendRequest(
          clientToServer,
          serverToClient,
          "tasks/result",
          Some(resultRequest.asJsonObject),
          "test-3"
        )
        _ = resultResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            val content = result("content").flatMap(_.asArray)
            assert(content.isDefined, "Result should have content")
            val meta = result("_meta").flatMap(_.asObject)
            assert(meta.isDefined, "Result should have _meta with related-task")
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tasks/cancel should cancel a running task") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(EchoTool[IO]),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("echo"),
        "arguments" -> Json.obj("message" -> Json.fromString("Hello!")),
        "task" -> Json.obj()
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest))
        taskId <- IO {
          response match {
            case JsonRpcResponse.Response(_, _, result) =>
              result.asJson.as[CreateTaskResult].toOption.map(_.task.taskId).getOrElse(fail("No taskId"))
            case other =>
              fail(s"Expected Response, got: $other")
          }
        }

        // Cancel the task
        cancelRequest = CancelTaskRequest(taskId = taskId)
        cancelResponse <- sendRequest(
          clientToServer,
          serverToClient,
          "tasks/cancel",
          Some(cancelRequest.asJsonObject),
          "test-2"
        )
        _ = cancelResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            val cancelResult = result.asJson.as[CancelTaskResult]
            cancelResult.toOption match {
              case Some(res) =>
                assertEquals(res.task.status, TaskStatus.cancelled)
              case None =>
                // Task may have already completed - that's ok
                ()
            }
          case JsonRpcResponse.Error(_, _, err) =>
            // Task may have already completed
            assert(err.message.contains("Cannot cancel"), s"Unexpected error: ${err.message}")
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tasks/get should return error for unknown task") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        getRequest = GetTaskRequest(taskId = "unknown-task-id")
        getResponse <- sendRequest(clientToServer, serverToClient, "tasks/get", Some(getRequest.asJsonObject))
        _ = getResponse match {
          case JsonRpcResponse.Error(_, _, err) =>
            assert(err.message.contains("not found"), s"Should report task not found: ${err.message}")
          case other =>
            fail(s"Expected Error, got: $other")
        }
      } yield ()
    }
  }

  test("tasks endpoints should return error when tasks not enabled") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tasksEnabled = false
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tasks/list")
        _ = response match {
          case JsonRpcResponse.Error(_, _, err) =>
            assert(err.message.contains("not enabled"), s"Should report tasks not enabled: ${err.message}")
          case other =>
            fail(s"Expected Error, got: $other")
        }
      } yield ()
    }
  }
}
