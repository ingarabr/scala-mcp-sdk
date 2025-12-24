package examples

import cats.effect.*
import mcp.protocol.*
import mcp.server.*
import examples.tools.{AddTool, EchoTool, LogAndProgressTool}
import examples.resources.{ServerConfigResource, TimestampResource}
import examples.prompts.GreetingPrompt

/** A simple example MCP server demonstrating the type-safe API.
  *
  * This example shows:
  *   - Importing primitive definitions from separate modules
  *   - Creating a server with declarative configuration
  *   - Serving over stdio transport
  *
  * Each primitive (tool, resource, prompt) is defined in its own file, making them reusable and easy to maintain.
  */
object SimpleServer extends IOApp.Simple {

  def run: IO[Unit] =
    McpServer[IO](
      info = Implementation("simple-server", "1.0.0"),
      tools = List(EchoTool[IO], AddTool[IO], LogAndProgressTool[IO]),
      resources = List(ServerConfigResource[IO], TimestampResource[IO]),
      prompts = List(GreetingPrompt[IO])
    ).use { server =>
      StdioTransport[IO]().use(transport => server.serve(transport))
    }
}
