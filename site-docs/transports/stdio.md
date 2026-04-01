---
sidebar_position: 2
---

# Stdio Transport

The stdio transport uses standard input/output for communication. It's the simplest transport option, ideal for CLI tools and desktop integrations.

## Basic Usage

```scala mdoc:compile-only
import cats.effect.*
import mcp.protocol.*
import mcp.server.*

type EchoInput = (message: String, upper: Option[Boolean])
given InputDef[EchoInput] = InputDef[EchoInput](
  message = InputField[String]("Message to echo"),
  upper   = InputField[Option[Boolean]]("Convert to uppercase")
)

object MyServer extends IOApp.Simple {
  val echoTool = ToolDef.unstructured[IO, EchoInput](
    name = "echo",
    description = Some("Echo input")
  ) { (input, ctx) =>
    IO.pure(List(Content.Text(input.message)))
  }

  def run: IO[Unit] = {
    (for {
      server <- McpServer[IO](
        info = Implementation("my-server", "1.0.0"),
        tools = List(echoTool)
      )
      transport <- StdioTransport[IO]()
      _ <- server.serve(transport)
    } yield ()).useForever
  }
}
```

## Client Configuration

### Claude Desktop

Add to your Claude Desktop config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "my-server": {
      "command": "scala-cli",
      "args": ["run", "/path/to/MyServer.scala"]
    }
  }
}
```

### With a Compiled JAR

```json
{
  "mcpServers": {
    "my-server": {
      "command": "java",
      "args": ["-jar", "/path/to/my-server.jar"]
    }
  }
}
```

## Logging Considerations

Since stdout is used for MCP messages, you cannot log to stdout directly. Options:

1. **Log to stderr** - Most logging frameworks support this
2. **Use MCP logging** - Send logs through the protocol
3. **Log to file** - Configure your logger to write to a file

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Json
import mcp.protocol.{Content, LoggingLevel}
import mcp.server.*

type MyInput = (data: String, verbose: Option[Boolean])
given InputDef[MyInput] = InputDef[MyInput](
  data    = InputField[String]("Data to process"),
  verbose = InputField[Option[Boolean]]("Enable verbose logging")
)

// Using MCP's built-in logging
val myTool = ToolDef.unstructured[IO, MyInput](
  name = "my-tool",
  description = Some("Does something")
) { (input, ctx) =>
  for {
    _ <- ctx.log(LoggingLevel.info, Json.fromString("Processing request..."))
  } yield List(Content.Text(s"Processed: ${input.data}"))
}
```

## Testing

Use the MCP Inspector to test your stdio server:

```bash
npx @anthropic/mcp-inspector scala-cli run MyServer.scala
```

## Graceful Shutdown

The transport handles shutdown automatically when stdin closes. For cleanup logic, use Cats Effect resources:

```scala
def run: IO[Unit] = {
  myResources.use { resources =>
    (for {
      server <- McpServer[IO](
        info = Implementation("my-server", "1.0.0"),
        tools = List(toolUsing(resources))
      )
      transport <- StdioTransport[IO]()
      _ <- server.serve(transport)
    } yield ()).useForever
  }
}
```
