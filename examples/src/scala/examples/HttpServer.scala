package examples

import cats.effect.{ExitCode, IO, IOApp}
import com.comcast.ip4s.*
import mcp.protocol.Implementation
import mcp.http4s.McpHttp4sServer
import mcp.server.McpServer
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.CORS

import examples.tools.{AddTool, EchoTool}
import examples.resources.ServerConfigResource
import examples.prompts.GreetingPrompt

/** HTTP server example using SSE transport.
  *
  * This demonstrates how to:
  *   1. Create an MCP server with tools, resources, and prompts
  *   2. Create an SSE transport for HTTP-based communication
  *   3. Set up an http4s server with the transport routes
  *   4. Add CORS middleware for web client support
  *
  * Run with: bleep run examples@HttpServer
  *
  * Test with:
  *   - SSE endpoint: curl http://localhost:8080/mcp/sse
  *   - POST endpoint: curl -X POST http://localhost:8080/mcp/message -H "Content-Type: application/json" -d
  *     '{"jsonrpc":"2.0","id":"1","method":"tools/list"}'
  */
object HttpServer extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    // Create MCP server with primitives
    val serverResource = McpServer[IO](
      info = Implementation("http-mcp-server", "1.0.0"),
      tools = List(EchoTool[IO], AddTool[IO]),
      resources = List(ServerConfigResource[IO]),
      prompts = List(GreetingPrompt[IO])
    )

    serverResource.use { mcpServer =>
      // Create SSE transport
      McpHttp4sServer[IO]().use { transport =>
        // Configure CORS for web clients
        val corsConfig = CORS.policy.withAllowOriginAll.withAllowMethodsAll.withAllowHeadersAll

        // Mount transport routes under /mcp
        val httpApp = Router(
          "/mcp" -> corsConfig(transport.routes)
        ).orNotFound

        // Start http4s server and MCP server concurrently
        val httpServer = EmberServerBuilder
          .default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(httpApp)
          .build

        val mcpService = mcpServer.serve(transport)

        IO.println("🚀 HTTP MCP Server starting...") *>
          IO.println("   SSE endpoint: http://localhost:8080/mcp/sse") *>
          IO.println("   POST endpoint: http://localhost:8080/mcp/message") *>
          IO.println("   Press Ctrl+C to stop") *>
          httpServer.use { _ =>
            mcpService.as(ExitCode.Success)
          }
      }
    }
  }
}
