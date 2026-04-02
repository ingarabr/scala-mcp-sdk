---
sidebar_position: 1
---

# Introduction

**scala-mcp-sdk** is a Scala 3 library for building [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) servers.

If you're new to MCP, start with the [official specification](https://modelcontextprotocol.io/docs/concepts/architecture) to understand the protocol concepts.

## Why This Library?

When this project started, there was no MCP SDK that integrated well with the Scala ecosystem. The existing options were Java-centric and didn't compose naturally with [Cats Effect](https://typelevel.org/cats-effect/), [fs2](https://fs2.io/), or [http4s](https://http4s.org/).

scala-mcp-sdk was built to fill that gap — a protocol library that feels native to the Typelevel stack and makes it straightforward to add MCP support to both stdio tools and existing http4s services.

## What This Library Provides

- **Protocol types** - Generated from the MCP JSON Schema
- **Server API** - Type-safe definitions for tools, resources, and prompts
- **Transports** - Stdio and Streamable HTTP implementations

## What You Provide

- **HTTP server** - If using HTTP transport (Ember, Blaze, etc.)
- **Logging, config, metrics** - Your existing infrastructure
- **Business logic** - Your tools, resources, and prompts

## Design Goals

### Protocol Library, Not Framework

We implement the protocol. You integrate it into your stack.

### Typelevel Ecosystem

Built on Cats Effect 3, fs2, http4s, and Circe.

## Quick Example

```scala mdoc:compile-only
import cats.effect.*
import mcp.protocol.*
import mcp.server.*

type GreetInput = (name: String, formal: Option[Boolean])
given InputDef[GreetInput] = InputDef[GreetInput](
  name   = InputField[String]("Name to greet"),
  formal = InputField[Option[Boolean]]("Use formal greeting")
)

object MyServer extends IOApp.Simple {
  val greetTool = ToolDef.unstructured[IO, GreetInput](
    name = "greet",
    description = Some("Greet someone")
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

## Next Steps

- [Getting Started](./getting-started.md) - Build and run your first server
- [Tools](./features/tools.md) - Expose callable functions
- [Resources](./features/resources.md) - Provide data access
- [Transports](./transports/overview.md) - Choose stdio or HTTP
