package examples

import cats.effect.IO
import com.comcast.ip4s.*
import examples.tools.{AddTool, EchoTool}
import io.circe.*
import mcp.http4s.session.{McpHttpRoutes, SessionManager}
import mcp.protocol.*
import mcp.server.McpServer
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Server
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** Integration test that replicates the MCP Inspector flow.
  *
  * Based on captured HTTP events from .research/debug/httplog.md
  */
class HttpIntegrationTest extends CatsEffectSuite {

  // Create and start HTTP server using same pattern as HttpServer example
  def startTestServer(port: Port): cats.effect.Resource[IO, Server] = {
    // Create MCP server with primitives (same pattern as HttpServer.scala)
    val serverResource = McpServer[IO](
      info = Implementation(name = "http-mcp-server", version = "1.0.0"),
      tools = List(EchoTool[IO], AddTool[IO])
    )

    serverResource.flatMap { mcpServer =>
      // Create session manager (same pattern as HttpServer.scala)
      SessionManager[IO](
        idleTimeout = 30.minutes,
        checkInterval = 5.minutes
      ).flatMap { sessionManager =>
        val routes = McpHttpRoutes.routes[IO](
          server = mcpServer,
          sessionManager = sessionManager,
          enableSessions = true
        )

        val httpApp = routes.orNotFound

        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port)
          .withHttpApp(httpApp)
          .build
      }
    }
  }

  test("Full MCP Inspector flow: initialize -> tools/list -> tools/call") {
    startTestServer(port"0").use { server =>
      EmberClientBuilder.default[IO].build.use { client =>
        val baseUri = Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}/mcp")

        // Step 1: Initialize
        val initRequest = Request[IO](Method.POST, baseUri)
          .withEntity(
            Json.obj(
              "jsonrpc" -> Json.fromString("2.0"),
              "id" -> Json.fromInt(0),
              "method" -> Json.fromString("initialize"),
              "params" -> Json.obj(
                "protocolVersion" -> Json.fromString("2025-11-25"),
                "capabilities" -> Json.obj(
                  "sampling" -> Json.obj()
                ),
                "clientInfo" -> Json.obj(
                  "name" -> Json.fromString("test-client"),
                  "version" -> Json.fromString("1.0.0")
                )
              )
            )
          )
          .putHeaders(
            Header.Raw(CIString("Accept"), "application/json, text/event-stream"),
            Header.Raw(CIString("Content-Type"), "application/json")
          )

        for {
          initResponse <- client.run(initRequest).use { response =>
            response.as[Json].map { body =>
              assertEquals(response.status, Status.Ok, "Initialize should return 200 OK")
              val sessionId = response.headers
                .get(CIString("Mcp-Session-Id"))
                .map(_.head.value)
                .getOrElse(fail("Missing Mcp-Session-Id header"))
              val serverName = body.hcursor.downField("result").downField("serverInfo").downField("name").as[String].toOption
              assert(serverName.contains("http-mcp-server"), "Server name should match")
              (sessionId, body)
            }
          }
          sessionId = initResponse._1

          // Step 2: Send initialized notification
          _ <- {
            val initializedRequest = Request[IO](Method.POST, baseUri)
              .withEntity(
                Json.obj(
                  "jsonrpc" -> Json.fromString("2.0"),
                  "method" -> Json.fromString("notifications/initialized")
                )
              )
              .putHeaders(
                Header.Raw(CIString("Mcp-Session-Id"), sessionId),
                Header.Raw(CIString("MCP-Protocol-Version"), "2025-11-25"),
                Header.Raw(CIString("Content-Type"), "application/json")
              )

            client.run(initializedRequest).use { response =>
              IO(assertEquals(response.status, Status.Accepted, "Initialized notification should return 202 Accepted"))
            }
          }

          // Step 3: List tools
          toolsBody <- {
            val toolsListRequest = Request[IO](Method.POST, baseUri)
              .withEntity(
                Json.obj(
                  "jsonrpc" -> Json.fromString("2.0"),
                  "id" -> Json.fromInt(1),
                  "method" -> Json.fromString("tools/list"),
                  "params" -> Json.obj(
                    "_meta" -> Json.obj("progressToken" -> Json.fromInt(1))
                  )
                )
              )
              .putHeaders(
                Header.Raw(CIString("Mcp-Session-Id"), sessionId),
                Header.Raw(CIString("MCP-Protocol-Version"), "2025-11-25"),
                Header.Raw(CIString("Content-Type"), "application/json")
              )

            client.run(toolsListRequest).use { response =>
              response.as[Json].map { body =>
                assertEquals(response.status, Status.Ok, "tools/list should return 200 OK")
                val tools = body.hcursor.downField("result").downField("tools").as[List[Json]].toOption.getOrElse(Nil)
                assert(tools.size == 2, s"Should have 2 tools, got ${tools.size}")
                body
              }
            }
          }

          // Step 4: Call echo tool
          _ <- {
            val callToolRequest = Request[IO](Method.POST, baseUri)
              .withEntity(
                Json.obj(
                  "jsonrpc" -> Json.fromString("2.0"),
                  "id" -> Json.fromInt(2),
                  "method" -> Json.fromString("tools/call"),
                  "params" -> Json.obj(
                    "_meta" -> Json.obj("progressToken" -> Json.fromInt(2)),
                    "name" -> Json.fromString("echo"),
                    "arguments" -> Json.obj("message" -> Json.fromString("asdasdas"))
                  )
                )
              )
              .putHeaders(
                Header.Raw(CIString("Mcp-Session-Id"), sessionId),
                Header.Raw(CIString("MCP-Protocol-Version"), "2025-11-25"),
                Header.Raw(CIString("Content-Type"), "application/json")
              )

            client.run(callToolRequest).use { response =>
              response.as[Json].map { body =>
                assertEquals(response.status, Status.Ok, "tools/call should return 200 OK")
                val echoResult = body.hcursor
                  .downField("result")
                  .downField("content")
                  .downArray
                  .downField("text")
                  .as[String]
                  .toOption
                assertEquals(echoResult, Some("Echo: asdasdas"), "Echo result should match")
              }
            }
          }
        } yield ()
      }
    }
  }
}
