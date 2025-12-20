package examples

import cats.effect.{ExitCode, IO, IOApp}
import com.comcast.ip4s.*
import mcp.protocol.Implementation
import mcp.http4s.session.{McpHttpRoutes, SessionManager}
import mcp.server.McpServer
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import examples.tools.{AddTool, EchoTool, LogAndProgressTool}
import examples.resources.{FileTemplateResource, ServerConfigResource}
import examples.prompts.GreetingPrompt

import scala.concurrent.duration.*

/** HTTP server example using session-based HTTP/SSE transport.
  *
  * Run with: bleep run examples@HttpServer
  */
object HttpServer extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    // Create MCP server with primitives
    val serverResource = McpServer[IO](
      info = Implementation("http-mcp-server", "1.0.0"),
      tools = List(EchoTool[IO], AddTool[IO], LogAndProgressTool[IO]),
      resources = List(ServerConfigResource[IO]),
      resourceTemplates = List(FileTemplateResource[IO]()),
      prompts = List(GreetingPrompt[IO])
    )

    serverResource.use { mcpServer =>
      // Create session manager with automatic cleanup
      SessionManager[IO](
        idleTimeout = 30.minutes, // Remove sessions idle for 30 minutes
        checkInterval = 5.minutes // Check every 5 minutes
      ).use { sessionManager =>
        val routes = McpHttpRoutes.routes[IO](
          server = mcpServer,
          sessionManager = sessionManager,
          enableSessions = true
        )

        val corsConfig = CORS.policy.withAllowOriginAll.withAllowMethodsAll.withAllowHeadersAll

        val httpApp = ColoredConsoleLogger[IO](
          logHeaders = true,
          logBody = true
        )(corsConfig(routes)).orNotFound

        val httpServer = EmberServerBuilder
          .default[IO]
//          .withHost(ipv4"0.0.0.0")
          .withHost(ipv4"127.0.0.1")
          .withPort(port"8080")
          .withHttpApp(httpApp)
          .build

        // Start server
        IO.println("""
          |🚀 HTTP MCP Server starting...
          |   Endpoint: http://localhost:8080/mcp
          |   Mode: Session-based (multi-client)
          |   Features:
          |     - POST /mcp: Initialize and send requests
          |     - GET /mcp: Persistent SSE stream
          |     - DELETE /mcp: Terminate session
          |   Session timeout: 30 minutes (checked every 5 minutes)
          |   Press Ctrl+C to stop
          |""".stripMargin) *>
          httpServer.use(_ => IO.never).as(ExitCode.Success)
      }
    }
  }
}
