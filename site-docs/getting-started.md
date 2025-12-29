---
sidebar_position: 2
---

# Getting Started

Build your first MCP server in Scala.

## Installation

Add the dependencies to your build:

```scala title="build.sbt"
libraryDependencies ++= Seq(
  "io.github.ingarabr" %% "scala-mcp-server" % "<version>",
  "io.github.ingarabr" %% "scala-mcp-transport-stdio" % "<version>"
)
```

Or with Bleep:

```yaml
# bleep.yaml
dependencies:
  - io.github.ingarabr::scala-mcp-server:<version>
  - io.github.ingarabr::scala-mcp-transport-stdio:<version>
```

Or with Mill:

```scala title="build.mill"
import mill._, scalalib._

object myserver extends ScalaModule {
  def scalaVersion = "3.3.4"
  def ivyDeps = Agg(
    ivy"io.github.ingarabr::scala-mcp-server:<version>",
    ivy"io.github.ingarabr::scala-mcp-transport-stdio:<version>"
  )
}
```

## Your First Server

Here's a minimal MCP server with a single tool:

```scala mdoc:compile-only
import cats.effect.*
import io.circe.*
import mcp.protocol.*
import mcp.server.*
import mcp.schema.{McpSchema, description}

// Define the input type with schema derivation
@description("Input for greeting")
case class GreetInput(
  @description("Name to greet")
  name: String
) derives Codec.AsObject

object GreetInput {
  given McpSchema[GreetInput] = McpSchema.derived
}

object MyServer extends IOApp.Simple {
  // Define the tool
  val greetTool = ToolDef.unstructured[IO, GreetInput](
    name = "greet",
    description = Some("Greet someone by name")
  ) { (input, ctx) =>
    IO.pure(List(Content.Text(s"Hello, ${input.name}!")))
  }

  def run: IO[Unit] = {
    (for {
      server <- McpServer[IO](
        info = Implementation("my-server", "1.0.0"),
        tools = List(greetTool)
      )
      transport <- StdioTransport[IO]()
      _ <- server.serve(transport)
    } yield ()).useForever
  }
}
```

## What's Happening?

1. **Input type** - Define a case class with `@description` annotations for schema generation
2. **`ToolDef.unstructured`** - Creates a tool that returns raw content
3. **`McpServer[IO](...)`** - Creates a server resource with your primitives
4. **`StdioTransport[IO]()`** - Creates a stdio transport resource
5. **`server.serve(transport)`** - Connects them together
6. **`.useForever`** - Runs until interrupted

## Testing with MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) lets you test your server:

```bash
npx @anthropic/mcp-inspector scala-cli run MyServer.scala
```

## Next Steps

Now that you have a basic server running:

- [Tools](./features/tools.md) - Learn about input schemas and tool patterns
- [Resources](./features/resources.md) - Expose data to clients
- [Prompts](./features/prompts.md) - Create reusable prompt templates
- [Streamable HTTP Transport](./transports/http.md) - Deploy as an HTTP service
