package examples

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.*
import mcp.server.{InputDef, InputField, McpServer, TaskMode, ToolDef, Transport}
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

  // Echo tool that supports task-augmented execution (client can choose sync or async)
  type TaskEchoInput = (message: String, uppercase: Option[Boolean])
  given InputDef[TaskEchoInput] = InputDef[TaskEchoInput](
    message = InputField[String]("The message to echo back"),
    uppercase = InputField[Option[Boolean]]("Whether to uppercase")
  )

  def asyncAllowedTool: ToolDef[IO, TaskEchoInput, Nothing] =
    ToolDef.unstructured[IO, TaskEchoInput](
      name = "async-allowed-echo",
      description = Some("Echo with optional task support"),
      taskMode = TaskMode.AsyncAllowed
    ) { (input, _) =>
      IO.pure(List(Content.Text(s"Echo: ${input.message}")))
    }

  // Echo tool that requires task-augmented execution
  def asyncOnlyTool: ToolDef[IO, TaskEchoInput, Nothing] =
    ToolDef.unstructured[IO, TaskEchoInput](
      name = "async-only-echo",
      description = Some("Echo that requires tasks"),
      taskMode = TaskMode.AsyncOnly
    ) { (input, _) =>
      IO.pure(List(Content.Text(s"Echo: ${input.message}")))
    }

  // Tool that always returns isError = true
  def failingTool: ToolDef[IO, TaskEchoInput, Nothing] =
    ToolDef.unstructured[IO, TaskEchoInput](
      name = "failing-tool",
      description = Some("Tool that returns an error result"),
      taskMode = TaskMode.AsyncAllowed
    ) { (_, _) =>
      IO.raiseError(new RuntimeException("Something went wrong"))
    }

  test("server with tasks enabled should advertise tasks capability") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(asyncAllowedTool),
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
      tools = List(asyncAllowedTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("async-allowed-echo"),
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
      tools = List(asyncAllowedTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("async-allowed-echo"),
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

  // === TaskMode enforcement tests ===

  test("SyncOnly tool rejects task params with METHOD_NOT_FOUND") {
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
        _ = response match {
          case JsonRpcResponse.Error(_, _, err) =>
            assertEquals(err.code, -32601, s"Should use METHOD_NOT_FOUND error code")
            assert(
              err.message.contains("does not support task-augmented execution"),
              s"Should explain rejection: ${err.message}"
            )
          case other =>
            fail(s"Expected Error, got: $other")
        }
      } yield ()
    }
  }

  test("AsyncOnly tool rejects non-task calls with METHOD_NOT_FOUND") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(asyncOnlyTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = CallToolRequest(
        name = "async-only-echo",
        arguments = Some(JsonObject("message" -> Json.fromString("Hello!")))
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest.asJsonObject))
        _ = response match {
          case JsonRpcResponse.Error(_, _, err) =>
            assertEquals(err.code, -32601, s"Should use METHOD_NOT_FOUND error code")
            assert(
              err.message.contains("requires task-augmented execution"),
              s"Should explain rejection: ${err.message}"
            )
          case other =>
            fail(s"Expected Error, got: $other")
        }
      } yield ()
    }
  }

  test("AsyncAllowed tool works without task params (sync mode)") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(asyncAllowedTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = CallToolRequest(
        name = "async-allowed-echo",
        arguments = Some(JsonObject("message" -> Json.fromString("Hello!")))
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest.asJsonObject))
        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val toolResult = result.asJson.as[CallToolResult]
            assert(toolResult.isRight, s"Should succeed without task params: $toolResult")
            toolResult.toOption.foreach { callResult =>
              assertEquals(callResult.isError, Some(false))
            }
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("server without tasks ignores task params and executes normally") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(EchoTool[IO]),
      tasksEnabled = false
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("echo"),
        "arguments" -> Json.obj("message" -> Json.fromString("Hello!")),
        "task" -> Json.obj("ttl" -> Json.fromLong(5000L))
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest))
        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val toolResult = result.asJson.as[CallToolResult]
            assert(toolResult.isRight, s"Should execute normally ignoring task params: $toolResult")
            toolResult.toOption.foreach { callResult =>
              assertEquals(callResult.isError, Some(false))
              val textContent = callResult.content.head.asInstanceOf[Content.Text].text
              assert(textContent.contains("Echo: Hello!"), s"Should have executed the tool: $textContent")
            }
          case other =>
            fail(s"Expected Response (task params should be ignored), got: $other")
        }
      } yield ()
    }
  }

  test("tool error (isError=true) sets task status to failed") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(failingTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("failing-tool"),
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

        // Wait for task to complete
        _ <- IO.sleep(200.millis)

        // Poll tasks/get — should be failed
        getRequest = GetTaskRequest(taskId = taskId)
        getResponse <- sendRequest(clientToServer, serverToClient, "tasks/get", Some(getRequest.asJsonObject), "test-2")
        _ = getResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            val getResult = result.asJson.as[GetTaskResult]
            getResult.toOption match {
              case Some(res) =>
                assertEquals(res.task.status, TaskStatus.failed, "Task should be failed when tool returns isError=true")
              case None =>
                fail(s"Failed to decode GetTaskResult from: $result")
            }
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tasks/result blocks until task completes") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(asyncAllowedTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("async-allowed-echo"),
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

        // Call tasks/result — should block and return the result once task completes
        resultRequest = GetTaskResultRequest(taskId = taskId)
        resultResponse <- sendRequest(
          clientToServer,
          serverToClient,
          "tasks/result",
          Some(resultRequest.asJsonObject),
          "test-2"
        )
        _ = resultResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            val meta = result("_meta").flatMap(_.asObject)
            assert(meta.isDefined, "Result should have _meta with related-task")
            val content = result("content").flatMap(_.asArray)
            assert(content.isDefined, "Result should have content")
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tasks/result returns result for failed tasks") {
    val serverResource = McpServer[IO](
      info = Implementation("test-server", "1.0.0"),
      tools = List(failingTool),
      tasksEnabled = true
    )

    withServer(serverResource) { (serverToClient, clientToServer) =>
      val callRequest = JsonObject(
        "name" -> Json.fromString("failing-tool"),
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

        // Wait for task to fail
        _ <- IO.sleep(200.millis)

        // tasks/result should return the error result, not a protocol error
        resultRequest = GetTaskResultRequest(taskId = taskId)
        resultResponse <- sendRequest(
          clientToServer,
          serverToClient,
          "tasks/result",
          Some(resultRequest.asJsonObject),
          "test-2"
        )
        _ = resultResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            val meta = result("_meta").flatMap(_.asObject)
            assert(meta.isDefined, "Failed task result should have _meta with related-task")
            val content = result("content").flatMap(_.asArray)
            assert(content.isDefined, "Failed task result should have content with error message")
          case other =>
            fail(s"Expected Response with error result, got: $other")
        }
      } yield ()
    }
  }
}
