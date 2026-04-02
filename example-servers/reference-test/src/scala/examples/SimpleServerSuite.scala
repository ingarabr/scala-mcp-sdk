package examples

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import mcp.protocol.*
import mcp.server.{McpServer, Transport}
import munit.CatsEffectSuite
import examples.tools.{AddTool, EchoTool, PingTool}
import examples.resources.ServerConfigResource
import examples.prompts.GreetingPrompt
import cats.effect.Resource

/** Test suite for the SimpleServer implementation.
  *
  * This simulates the actual message sequence that an LLM (like Claude) would send when using MCP. The typical flow is:
  *   1. Initialize connection (handshake)
  *   2. Discover available tools (tools/list)
  *   3. Call tools based on user requests (tools/call)
  *   4. Access resources when needed (resources/read)
  *   5. Use prompts for structured interactions (prompts/get)
  */
class SimpleServerSuite extends CatsEffectSuite {

  /** Helper to run a test with McpServer and TestTransport as a Resource.
    *
    * Creates the transport, starts the server with it, and provides the queues to the test.
    */
  def withServer[A](serverResource: Resource[IO, McpServer[IO]])(
      test: (Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]]) => IO[A]
  ): IO[A] =
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      (for {
        server <- serverResource
        _ <- server.serve(transport)
      } yield ()).use(_ => test(serverToClient, clientToServer))
    }

  /** In-memory transport for testing - simulates client-server communication */
  class TestTransport(
      serverToClient: Queue[IO, Option[JsonRpcResponse]],
      clientToServer: Queue[IO, Option[JsonRpcRequest]]
  ) extends Transport[IO] {

    def receive: Stream[IO, JsonRpcRequest] =
      Stream.fromQueueNoneTerminated(clientToServer)

    def send(message: JsonRpcResponse): IO[Unit] =
      serverToClient.offer(Some(message)).void

    def sendRequest(method: String, params: Option[io.circe.JsonObject]): IO[Either[mcp.protocol.ErrorData, io.circe.JsonObject]] =
      IO.raiseError(new NotImplementedError("TestTransport.sendRequest not implemented"))
  }

  object TestTransport {
    def create: IO[(TestTransport, Queue[IO, Option[JsonRpcResponse]], Queue[IO, Option[JsonRpcRequest]])] =
      for {
        serverToClient <- Queue.unbounded[IO, Option[JsonRpcResponse]]
        clientToServer <- Queue.unbounded[IO, Option[JsonRpcRequest]]
      } yield (new TestTransport(serverToClient, clientToServer), serverToClient, clientToServer)
  }

  /** Helper to send a request and get a response */
  def sendRequest(
      clientToServer: Queue[IO, Option[JsonRpcRequest]],
      serverToClient: Queue[IO, Option[JsonRpcResponse]],
      method: String,
      params: Option[JsonObject] = None
  ): IO[JsonRpcResponse] = {
    val request = JsonRpcRequest.Request(
      jsonrpc = Constants.JSONRPC_VERSION,
      id = RequestId("test-1"),
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

  /** Helper to initialize the server (required before calling any other methods) */
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
      // Send initialize request
      _ <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequest.asJsonObject))
      // Send initialized notification
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

  test("initialize handshake should succeed") {
    TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
      (for {
        server <- McpServer[IO](
          info = Implementation("test-server", "1.0.0"),
          tools = List(EchoTool[IO], AddTool[IO]),
          resources = List(ServerConfigResource[IO]),
          prompts = List(GreetingPrompt[IO])
        )
        _ <- server.serve(transport)
      } yield (serverToClient, clientToServer)).use { case (serverToClient, clientToServer) =>
        val initRequest = InitializeRequest(
          protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
          capabilities = ClientCapabilities(),
          clientInfo = Implementation("test-client", "1.0.0")
        )
        for {
          // Send initialize request
          response <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequest.asJsonObject))

          // Verify response
          _ = response match {
            case JsonRpcResponse.Response(_, _, result) =>
              val initResult = result.asJson.as[InitializeResult]
              assert(initResult.isRight, s"Failed to decode InitializeResult: $initResult")
              initResult.toOption match {
                case Some(initRes) =>
                  assertEquals(initRes.protocolVersion, Constants.LATEST_PROTOCOL_VERSION)
                  assertEquals(initRes.serverInfo.name, "test-server")
                  assert(initRes.capabilities.tools.isDefined, "Server should have tools capability")
                  assert(initRes.capabilities.resources.isDefined, "Server should have resources capability")
                  assert(initRes.capabilities.prompts.isDefined, "Server should have prompts capability")
                case None =>
                  fail(s"Failed to decode InitializeResult from: $result")
              }

            case other =>
              fail(s"Expected Response, got: $other")
          }

          // Send initialized notification
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
    }
  }

  test("ping should respond immediately") {
    withServer(McpServer[IO](info = Implementation("test-server", "1.0.0"))) { (serverToClient, clientToServer) =>
      for {
        // Send ping request
        response <- sendRequest(clientToServer, serverToClient, "ping", None)

        // Verify response is EmptyResult
        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val emptyResult = result.asJson.as[EmptyResult]
            assert(emptyResult.isRight, s"Failed to decode EmptyResult: $emptyResult")
          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tools/list should return all registered tools") {
    withServer(
      McpServer[IO](
        info = Implementation("test-server", "1.0.0"),
        tools = List(EchoTool[IO], AddTool[IO])
      )
    ) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/list")

        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val toolsResult = result.asJson.as[ListToolsResult]
            assert(toolsResult.isRight, s"Failed to decode ListToolsResult: $toolsResult")
            toolsResult.toOption match {
              case Some(listResult) =>
                val tools = listResult.tools
                assertEquals(tools.length, 2, "Should have 2 tools")
                assert(tools.exists(_.name == "echo"), "Should have echo tool")
                assert(tools.exists(_.name == "add"), "Should have add tool")

                // Verify add tool schema has properties
                tools.find(_.name == "add") match {
                  case Some(addTool) =>
                    addTool.inputSchema match {
                      case JsonSchemaType.ObjectSchema(properties, _, _, _) =>
                        assert(properties.isDefined, "Add tool should have properties in schema")
                        assert(properties.get.contains("a"), "Add tool should have field 'a' in schema")
                        assert(properties.get.contains("b"), "Add tool should have field 'b' in schema")
                      case _ =>
                        fail("Add tool inputSchema should be an ObjectSchema")
                    }
                  case None =>
                    fail("Add tool not found in tools list")
                }
              case None =>
                fail(s"Failed to decode ListToolsResult from: $result")
            }

          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tools/call echo should work correctly") {
    withServer(
      McpServer[IO](info = Implementation("test-server", "1.0.0"), tools = List(EchoTool[IO]))
    ) { (serverToClient, clientToServer) =>
      val callRequest = CallToolRequest(
        name = "echo",
        arguments = Some(JsonObject("message" -> Json.fromString("Hello, MCP!")))
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest.asJsonObject))

        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val toolResult = result.asJson.as[CallToolResult]
            assert(toolResult.isRight, s"Failed to decode CallToolResult: $toolResult")
            toolResult.toOption match {
              case Some(callResult) =>
                assertEquals(callResult.isError, Some(false), "Tool should execute without error")
                assert(callResult.content.nonEmpty, "Should have content")
                val textContent = callResult.content.head.asInstanceOf[Content.Text].text
                assert(textContent.contains("Echo: Hello, MCP!"), s"Output should contain echoed message, got: $textContent")
              case None =>
                fail(s"Failed to decode CallToolResult from: $result")
            }

          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tools/call ping (no-args tool) should work correctly") {
    withServer(
      McpServer[IO](info = Implementation("test-server", "1.0.0"), tools = List(PingTool[IO]))
    ) { (serverToClient, clientToServer) =>
      val callRequest = CallToolRequest(name = "ping")
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest.asJsonObject))

        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val toolResult = result.asJson.as[CallToolResult]
            assert(toolResult.isRight, s"Failed to decode CallToolResult: $toolResult")
            toolResult.toOption match {
              case Some(callResult) =>
                assertEquals(callResult.isError, Some(false))
                val textContent = callResult.content.head.asInstanceOf[Content.Text].text
                assertEquals(textContent, "pong")
              case None =>
                fail(s"Failed to decode CallToolResult from: $result")
            }

          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("tools/call add should work correctly") {
    withServer(
      McpServer[IO](info = Implementation("test-server", "1.0.0"), tools = List(AddTool[IO]))
    ) { (serverToClient, clientToServer) =>
      val callRequest = CallToolRequest(
        name = "add",
        arguments = Some(
          JsonObject(
            "a" -> Json.fromDoubleOrNull(5.0),
            "b" -> Json.fromDoubleOrNull(3.0),
            "c" -> Json.fromInt(0)
          )
        )
      )
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "tools/call", Some(callRequest.asJsonObject))

        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val toolResult = result.asJson.as[CallToolResult]
            assert(toolResult.isRight, s"Failed to decode CallToolResult: $toolResult")
            toolResult.toOption match {
              case Some(callResult) =>
                assertEquals(callResult.isError, Some(false), "Tool should execute without error")
                assert(callResult.content.nonEmpty, "Should have content")
                val textContent = callResult.content.head.asInstanceOf[Content.Text].text
                parse(textContent).toOption match {
                  case Some(outputJson) =>
                    val resultField = outputJson.hcursor.get[Double]("result")
                    assertEquals(resultField, Right(8.0), "Should add 5.0 + 3.0 = 8.0")
                  case None =>
                    fail(s"Failed to parse JSON from tool output: $textContent")
                }
              case None =>
                fail(s"Failed to decode CallToolResult from: $result")
            }

          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("resources/list should return all registered resources") {
    withServer(
      McpServer[IO](info = Implementation("test-server", "1.0.0"), resources = List(ServerConfigResource[IO]))
    ) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "resources/list")

        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val resourcesResult = result.asJson.as[ListResourcesResult]
            assert(resourcesResult.isRight, s"Failed to decode ListResourcesResult: $resourcesResult")
            resourcesResult.toOption match {
              case Some(listResult) =>
                val resources = listResult.resources
                assertEquals(resources.length, 1, "Should have 1 resource")
                assertEquals(resources.head.name, "Server Configuration")
                assertEquals(resources.head.uri, "config://server.json")
              case None =>
                fail(s"Failed to decode ListResourcesResult from: $result")
            }

          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("prompts/list should return all registered prompts") {
    withServer(
      McpServer[IO](info = Implementation("test-server", "1.0.0"), prompts = List(GreetingPrompt[IO]))
    ) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)
        response <- sendRequest(clientToServer, serverToClient, "prompts/list")

        _ = response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val promptsResult = result.asJson.as[ListPromptsResult]
            assert(promptsResult.isRight, s"Failed to decode ListPromptsResult: $promptsResult")
            promptsResult.toOption match {
              case Some(listResult) =>
                val prompts = listResult.prompts
                assertEquals(prompts.length, 1, "Should have 1 prompt")
                assertEquals(prompts.head.name, "greeting")
              case None =>
                fail(s"Failed to decode ListPromptsResult from: $result")
            }

          case other =>
            fail(s"Expected Response, got: $other")
        }
      } yield ()
    }
  }

  test("simulate complete LLM workflow - user asks to add two numbers") {
    withServer(
      McpServer[IO](
        info = Implementation("simple-server", "1.0.0"),
        tools = List(EchoTool[IO], AddTool[IO]),
        resources = List(ServerConfigResource[IO]),
        prompts = List(GreetingPrompt[IO])
      )
    ) { (serverToClient, clientToServer) =>
      val initRequest = InitializeRequest(
        protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
        capabilities = ClientCapabilities(),
        clientInfo = Implementation("claude-desktop", "1.0.0")
      )
      for {
        // Step 1: LLM initializes connection
        initResponse <- sendRequest(clientToServer, serverToClient, "initialize", Some(initRequest.asJsonObject))
        _ = initResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            result.asJson.as[InitializeResult].toOption match {
              case Some(_) => ()
              case None    =>
                fail(s"Failed to decode InitializeResult from: $result")
            }
          case other => fail(s"Unexpected response: $other")
        }

        // Send initialized notification (no response expected per JSON-RPC spec)
        _ <- clientToServer.offer(
          Some(JsonRpcRequest.Notification(Constants.JSONRPC_VERSION, "notifications/initialized", None))
        )

        // Step 2: LLM discovers available tools
        toolsResponse <- sendRequest(clientToServer, serverToClient, "tools/list")
        _ = toolsResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            result.asJson.as[ListToolsResult] match {
              case Right(_)  => ()
              case Left(err) =>
                fail(s"Failed to decode ListToolsResult. Error: $err, Raw result: ${result.asJson.spaces2}")
            }
          case other => fail(s"Unexpected response: $other")
        }

        // Step 3: User asks "What is 42 + 17?"
        // Step 4: LLM calls the add tool
        addRequest = CallToolRequest(
          name = "add",
          arguments = Some(
            JsonObject(
              "a" -> Json.fromDoubleOrNull(42.0),
              "b" -> Json.fromDoubleOrNull(17.0),
              "c" -> Json.fromInt(0)
            )
          )
        )
        addResponse <- sendRequest(clientToServer, serverToClient, "tools/call", Some(addRequest.asJsonObject))
        _ = addResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            result.asJson.as[CallToolResult].toOption match {
              case Some(callResult) =>
                assert(callResult.isError.contains(false), "Tool execution should succeed")
                val textContent = callResult.content.head.asInstanceOf[Content.Text].text
                parse(textContent).toOption match {
                  case Some(outputJson) =>
                    outputJson.hcursor.get[Double]("result").toOption match {
                      case Some(resultValue) =>
                        assertEquals(resultValue, 59.0, "42 + 17 should equal 59")
                      case None =>
                        fail(s"Failed to extract 'result' field from: $outputJson")
                    }
                  case None =>
                    fail(s"Failed to parse JSON from tool output: $textContent")
                }
              case None =>
                fail(s"Failed to decode CallToolResult from: $result")
            }
          case other => fail(s"Unexpected response: $other")
        }
      } yield ()
    }
  }

  test("simulate LLM workflow - user asks for server configuration") {
    withServer(
      McpServer[IO](info = Implementation("simple-server", "1.0.0"), resources = List(ServerConfigResource[IO]))
    ) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)

        // Step 1: LLM lists available resources
        resourcesResponse <- sendRequest(clientToServer, serverToClient, "resources/list")
        resourceUri <- IO {
          resourcesResponse match {
            case JsonRpcResponse.Response(_, _, result) =>
              result.asJson.as[ListResourcesResult].toOption match {
                case Some(resourcesResult) if resourcesResult.resources.nonEmpty =>
                  resourcesResult.resources.head.uri
                case Some(_) =>
                  fail("No resources found in response")
                case None =>
                  fail(s"Failed to decode ListResourcesResult from: $result")
              }
            case other => fail(s"Unexpected response: $other")
          }
        }

        // Step 2: LLM reads the specific resource
        readRequest = ReadResourceRequest(uri = resourceUri)
        readResponse <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readRequest.asJsonObject))
        _ = readResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            result.asJson.as[ReadResourceResult].toOption match {
              case Some(readResult) if readResult.contents.nonEmpty =>
                val content = readResult.contents.head.asInstanceOf[ResourceContents.Text]
                assert(content.text.contains("simple-server"), "Should contain server name")
                assert(content.text.contains("1.0.0"), "Should contain version")
              case Some(_) =>
                fail("No contents found in ReadResourceResult")
              case None =>
                fail(s"Failed to decode ReadResourceResult from: $result")
            }
          case other => fail(s"Unexpected response: $other")
        }
      } yield ()
    }
  }

  test("simulate LLM workflow - using greeting prompt") {
    withServer(
      McpServer[IO](info = Implementation("simple-server", "1.0.0"), prompts = List(GreetingPrompt[IO]))
    ) { (serverToClient, clientToServer) =>
      for {
        _ <- initializeServer(clientToServer, serverToClient)

        // Step 1: Get the prompt with arguments
        promptRequest = GetPromptRequest(
          name = "greeting",
          arguments = Some(JsonObject("name" -> Json.fromString("Alice")))
        )
        promptResponse <- sendRequest(clientToServer, serverToClient, "prompts/get", Some(promptRequest.asJsonObject))
        _ = promptResponse match {
          case JsonRpcResponse.Response(_, _, result) =>
            result.asJson.as[GetPromptResult].toOption match {
              case Some(promptResult) =>
                promptResult.messages.foreach { msg =>
                  val text = msg.content.asInstanceOf[Content.Text].text
                  assert(text.contains("Alice"), "Greeting should include the name")
                }
              case None =>
                fail(s"Failed to decode GetPromptResult from: $result")
            }
          case other => fail(s"Unexpected response: $other")
        }
      } yield ()
    }
  }
}
