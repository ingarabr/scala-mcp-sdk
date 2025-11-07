package examples

import cats.effect.*
import mcp.protocol.*
import mcp.server.*
import examples.tools.{AddTool, EchoTool}
import examples.resources.ServerConfigResource
import examples.prompts.GreetingPrompt

/** A simple example MCP server demonstrating the type-safe API.
  *
  * This example shows:
  *   - Importing primitive definitions from separate modules
  *   - Creating a server with declarative configuration
  *   - Serving over stdio transport
  *
  * Each primitive (tool, resource, prompt) is defined in its own file, making them reusable and easy to maintain.
  *
  * Run with: bleep run examples
  */
object SimpleServer extends IOApp.Simple {

  def run: IO[Unit] =
    // Create and run the server with imported primitives
    McpServer[IO](
      info = Implementation("simple-server", "1.0.0"),
      tools = List(EchoTool[IO], AddTool[IO]),
      resources = List(ServerConfigResource[IO]),
      prompts = List(GreetingPrompt[IO])
    ).use { server =>
      StdioTransport[IO]().use(transport => server.serve(transport))
    }
}
