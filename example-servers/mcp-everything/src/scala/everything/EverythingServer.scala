package everything

import cats.effect.*
import mcp.protocol.*
import mcp.server.*
import everything.tools.*
import everything.resources.*
import everything.prompts.*

/** Everything MCP Server - A comprehensive test server for MCP clients.
  *
  * This server exercises all features of the MCP protocol, modeled after the TypeScript reference implementation at
  * https://github.com/modelcontextprotocol/servers/tree/main/src/everything
  *
  * Features:
  *   - Tools: echo, add, tiny image, long-running operations with progress, sampling, environment variables, annotated messages
  *   - Resources: static text/blob resources, dynamic resource templates
  *   - Prompts: simple, with arguments, with completions, with embedded resources
  *   - Notifications: resource updates, list changes
  */
object EverythingServer extends IOApp.Simple {

  def run: IO[Unit] =
    McpServer[IO](
      info = Implementation("everything-server", "1.0.0"),
      tools = List(
        EchoTool[IO],
        AddTool[IO],
        TinyImageTool[IO],
        LongRunningTool[IO],
        SamplingTool[IO],
        GetEnvTool[IO],
        AnnotatedMessageTool[IO],
        GetRootsTool[IO]
      ),
      resources = List(
        StaticTextResource[IO],
        StaticBlobResource[IO]
      ),
      resourceTemplates = List(
        DynamicTextResourceTemplate[IO],
        DynamicBlobResourceTemplate[IO]
      ),
      prompts = List(
        SimplePrompt[IO],
        ArgumentsPrompt[IO],
        EmbeddedResourcePrompt[IO]
      ),
      completions = List(
        ArgumentsPromptCompletion[IO]
      )
    ).use { server =>
      StdioTransport[IO]().use(transport => server.serve(transport))
    }
}
