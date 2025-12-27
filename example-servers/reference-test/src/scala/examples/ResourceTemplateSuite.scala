package examples

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import io.circe.generic.semiauto.*
import mcp.protocol.*
import mcp.server.{McpServer, ResourceContext, ResourceContextImpl, ResourceDef, ResourceTemplateDef, Transport}
import munit.CatsEffectSuite
import examples.resources.FileTemplateResource

/** Test suite for resource templates functionality.
  *
  * Tests the dynamic resource resolution via URI templates (RFC 6570).
  */
class ResourceTemplateSuite extends CatsEffectSuite {

  /** Simple test output type for resources */
  case class FileContent(path: String, content: String) derives Codec.AsObject

  /** In-memory transport for testing */
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

  /** Helper to send a request and get a response.
    *
    * Skips any notifications (e.g., logging) and waits for the actual Response or Error.
    */
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

    def waitForResponse: IO[JsonRpcResponse] =
      serverToClient.take.flatMap {
        case Some(response) =>
          response match {
            case _: JsonRpcResponse.Notification =>
              // Skip notifications (e.g., logging), wait for actual response
              waitForResponse
            case r @ (_: JsonRpcResponse.Response | _: JsonRpcResponse.Error) =>
              IO.pure(r)
          }
        case None =>
          IO.raiseError(new RuntimeException("Server closed connection"))
      }

    clientToServer.offer(Some(request)) *> waitForResponse
  }

  /** Create a test resource template */
  def fileTemplate: ResourceTemplateDef[IO] =
    ResourceTemplateDef[IO](
      uriTemplate = "file:///{path}",
      name = "Workspace Files",
      description = Some("Read files from the workspace"),
      mimeType = Some("application/json"),
      resolver = { (params, _) =>
        val path = params.getOrElse("path", "unknown")
        // Simulate file content based on path
        path match {
          case "missing.txt" =>
            // File doesn't exist
            IO.pure(None)
          case _ =>
            val content = path match {
              case "config.json" => """{"setting": "value"}"""
              case "readme.md"   => "# Project README"
              case _             => s"Contents of $path"
            }
            IO.pure(
              Some(
                ResourceDef[IO, FileContent](
                  uri = s"file:///$path",
                  name = path,
                  description = Some(s"File: $path"),
                  handler = _ => IO.pure(FileContent(path, content))
                )
              )
            )
        }
      }
    )

  /** Create a test config template */
  def configTemplate: ResourceTemplateDef[IO] =
    ResourceTemplateDef[IO](
      uriTemplate = "config:///{section}",
      name = "Configuration Sections",
      description = Some("Access configuration by section"),
      resolver = { (params, _) =>
        val section = params.getOrElse("section", "default")
        val config = section match {
          case "database" => Map("host" -> "localhost", "port" -> "5432")
          case "network"  => Map("timeout" -> "30", "retries" -> "3")
          case _          => Map("status" -> "not found")
        }
        IO.pure(
          Some(
            ResourceDef[IO, Map[String, String]](
              uri = s"config:///$section",
              name = s"Config: $section",
              handler = _ => IO.pure(config)
            )(using Encoder.encodeMap[String, String])
          )
        )
      }
    )

  /** Initialize the server */
  def initializeServer(
      clientToServer: Queue[IO, Option[JsonRpcRequest]],
      serverToClient: Queue[IO, Option[JsonRpcResponse]]
  ): IO[Unit] = {
    val initParams = JsonObject(
      "protocolVersion" -> "2025-03-26".asJson,
      "capabilities" -> JsonObject.empty.asJson,
      "clientInfo" -> JsonObject("name" -> "test-client".asJson, "version" -> "1.0.0".asJson).asJson
    )
    sendRequest(clientToServer, serverToClient, "initialize", Some(initParams)).void
  }

  test("resources/templates/list returns all templates") {
    McpServer[IO](
      Implementation(name = "template-test", version = "1.0.0"),
      resourceTemplates = List(fileTemplate, configTemplate)
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/templates/list")
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val templates = result.asJson.hcursor.downField("resourceTemplates").as[List[Json]]
            assert(templates.isRight)
            assertEquals(templates.toOption.get.length, 2)

            val templateNames = templates.toOption.get.flatMap(_.hcursor.downField("name").as[String].toOption)
            assert(templateNames.contains("Workspace Files"))
            assert(templateNames.contains("Configuration Sections"))

          case other =>
            fail(s"Expected Response, got $other")
        }
      }
    }
  }

  test("resources/read resolves template and reads resource") {
    McpServer[IO](
      Implementation(name = "template-test", version = "1.0.0"),
      resourceTemplates = List(fileTemplate)
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        val readParams = JsonObject("uri" -> "file:///config.json".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val contents = result.asJson.hcursor.downField("contents").as[List[Json]]
            assert(contents.isRight, s"Failed to parse contents: $contents")

            val firstContent = contents.toOption.get.head
            val text = firstContent.hcursor.downField("text").as[String]
            assert(text.isRight)
            // The response contains the FileContent with path and content fields
            assert(text.toOption.get.contains("config.json"))
            assert(text.toOption.get.contains("setting"))

          case JsonRpcResponse.Error(_, _, error) =>
            fail(s"Got error: ${error.message}")

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("resources/read returns error when template resolver returns None") {
    McpServer[IO](
      Implementation(name = "template-test", version = "1.0.0"),
      resourceTemplates = List(fileTemplate)
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        // Request a file that the resolver returns None for
        val readParams = JsonObject("uri" -> "file:///missing.txt".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Error(_, _, error) =>
            assert(error.message.contains("not found"))

          case JsonRpcResponse.Response(_, _, result) =>
            fail(s"Expected error, got result: $result")

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("resources/read prioritizes static resources over templates") {
    val staticResource = ResourceDef[IO, String](
      uri = "file:///static.txt",
      name = "Static File",
      handler = _ => IO.pure("Static content - should be returned")
    )

    McpServer[IO](
      Implementation(name = "template-test", version = "1.0.0"),
      resources = List(staticResource),
      resourceTemplates = List(fileTemplate)
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        // Request the static resource that also matches the template
        val readParams = JsonObject("uri" -> "file:///static.txt".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val contents = result.asJson.hcursor.downField("contents").as[List[Json]]
            assert(contents.isRight)

            val text = contents.toOption.get.head.hcursor.downField("text").as[String]
            assert(text.isRight)
            // Should get the static content, not template-resolved content
            assert(text.toOption.get.contains("Static content"))

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("capabilities advertise resources when only templates are registered") {
    McpServer[IO](
      Implementation(name = "template-test", version = "1.0.0"),
      resourceTemplates = List(fileTemplate)
    ).use { server =>
      IO {
        val caps = server.capabilities
        assert(caps.resources.isDefined, "Resources capability should be present when templates are registered")
      }
    }
  }

  test("config template resolves different sections") {
    McpServer[IO](
      Implementation(name = "template-test", version = "1.0.0"),
      resourceTemplates = List(configTemplate)
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        val readParams = JsonObject("uri" -> "config:///database".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val contents = result.asJson.hcursor.downField("contents").as[List[Json]]
            assert(contents.isRight)

            val text = contents.toOption.get.head.hcursor.downField("text").as[String]
            assert(text.isRight)
            assert(text.toOption.get.contains("localhost"))
            assert(text.toOption.get.contains("5432"))

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  // ===== FileTemplateResource Example Tests =====

  test("FileTemplateResource reads readme.md") {
    McpServer[IO](
      Implementation(name = "file-template-test", version = "1.0.0"),
      resourceTemplates = List(FileTemplateResource[IO])
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        val readParams = JsonObject("uri" -> "file:///readme.md".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val contents = result.asJson.hcursor.downField("contents").as[List[Json]]
            assert(contents.isRight)

            val text = contents.toOption.get.head.hcursor.downField("text").as[String]
            assert(text.isRight)
            assert(text.toOption.get.contains("Example Project"))
            assert(text.toOption.get.contains("Getting Started"))

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("FileTemplateResource reads config.json") {
    McpServer[IO](
      Implementation(name = "file-template-test", version = "1.0.0"),
      resourceTemplates = List(FileTemplateResource[IO])
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        val readParams = JsonObject("uri" -> "file:///config.json".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val contents = result.asJson.hcursor.downField("contents").as[List[Json]]
            assert(contents.isRight)

            val text = contents.toOption.get.head.hcursor.downField("text").as[String]
            assert(text.isRight)
            assert(text.toOption.get.contains("my-app"))
            assert(text.toOption.get.contains("database"))

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("FileTemplateResource reads nested path src/main.scala") {
    McpServer[IO](
      Implementation(name = "file-template-test", version = "1.0.0"),
      resourceTemplates = List(FileTemplateResource[IO])
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        // Note: slashes in path get percent-encoded by UriTemplate
        val readParams = JsonObject("uri" -> "file:///src%2Fmain.scala".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val contents = result.asJson.hcursor.downField("contents").as[List[Json]]
            assert(contents.isRight)

            val text = contents.toOption.get.head.hcursor.downField("text").as[String]
            assert(text.isRight)
            assert(text.toOption.get.contains("Hello, MCP!"))

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("FileTemplateResource returns error for non-existent file") {
    McpServer[IO](
      Implementation(name = "file-template-test", version = "1.0.0"),
      resourceTemplates = List(FileTemplateResource[IO])
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        val readParams = JsonObject("uri" -> "file:///nonexistent.txt".asJson)

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/read", Some(readParams))
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Error(_, _, error) =>
            assert(error.message.contains("not found"))

          case JsonRpcResponse.Response(_, _, result) =>
            fail(s"Expected error, got result: $result")

          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
  }

  test("FileTemplateResource is listed in templates/list") {
    McpServer[IO](
      Implementation(name = "file-template-test", version = "1.0.0"),
      resourceTemplates = List(FileTemplateResource[IO])
    ).use { server =>
      TestTransport.create.flatMap { case (transport, serverToClient, clientToServer) =>
        val serverFiber = server.serve(transport).start

        for {
          fiber <- serverFiber
          _ <- initializeServer(clientToServer, serverToClient)
          response <- sendRequest(clientToServer, serverToClient, "resources/templates/list")
          _ <- clientToServer.offer(None)
          _ <- fiber.join
        } yield response match {
          case JsonRpcResponse.Response(_, _, result) =>
            val templates = result.asJson.hcursor.downField("resourceTemplates").as[List[Json]]
            assert(templates.isRight)
            assertEquals(templates.toOption.get.length, 1)

            val template = templates.toOption.get.head
            val name = template.hcursor.downField("name").as[String]
            val uriTemplate = template.hcursor.downField("uriTemplate").as[String]

            assertEquals(name.toOption, Some("Workspace Files"))
            assertEquals(uriTemplate.toOption, Some("file:///{path}"))

          case other =>
            fail(s"Expected Response, got $other")
        }
      }
    }
  }
}
