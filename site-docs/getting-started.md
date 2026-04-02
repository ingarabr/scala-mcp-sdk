---
sidebar_position: 2
---

# Getting Started

Build your first MCP server in Scala.

:::tip New to Cats Effect?
If you're coming from Java/Kotlin or haven't used the Typelevel stack before, read [Key Concepts](./concepts.md) first — it covers `IO`, `Resource`, and other patterns used throughout this guide.
:::

## Installation

Requires **Scala 3.7+**.

Add the dependencies to your build:

```scala title="build.sbt"
libraryDependencies += "com.github.ingarabr.mcp" %% "server" % "@VERSION@"
```

Or with Bleep:

```yaml
# bleep.yaml
dependencies:
  - com.github.ingarabr.mcp::server:@VERSION@
```

Or with Mill:

```scala title="build.mill"
import mill._, scalalib._

object myserver extends ScalaModule {
  def scalaVersion = "3.7.3"
  def ivyDeps = Agg(
    ivy"com.github.ingarabr.mcp::server:@VERSION@"
  )
}
```

The `server` module includes stdio transport. For HTTP transport, also add `server-http4s`.

## Your First Server

Here's a minimal MCP server with a single tool:

```scala mdoc:compile-only
import cats.effect.*
import mcp.protocol.*
import mcp.server.*

// Define the input type as a named tuple with field descriptors
type GreetInput = (name: String, excited: Option[Boolean])
given InputDef[GreetInput] = InputDef[GreetInput](
  name    = InputField[String]("Name to greet"),
  excited = InputField[Option[Boolean]]("Add exclamation marks")
)

object MyServer extends IOApp.Simple {
  val greetTool = ToolDef.unstructured[IO, GreetInput](
    name = "greet",
    description = Some("Greet someone by name")
  ) { (input, ctx) =>
    val mark = if input.excited.getOrElse(false) then "!!!" else "!"
    IO.pure(List(Content.Text(s"Hello, ${input.name}$mark")))
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

1. **Input type** - Define a named tuple with `InputField` descriptors for schema generation
2. **`ToolDef.unstructured`** - Creates a tool that returns raw content (resolves `InputDef` via `using`)
3. **`McpServer[IO](...)`** - Creates a server resource with your primitives
4. **`StdioTransport[IO]()`** - Creates a stdio transport resource
5. **`server.serve(transport)`** - Connects them together
6. **`.useForever`** - Runs until interrupted

## Running Your Server

With sbt:

```bash
sbt run
```

With scala-cli (single file):

```bash
scala-cli run MyServer.scala
```

Your server is now listening on stdin/stdout for MCP messages.

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
