# scala-mcp-sdk

A pure Scala 3 implementation of the [Model Context Protocol](https://modelcontextprotocol.io/) (MCP).

[![License: MIT](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

## Overview

**scala-mcp-sdk** is a protocol library for building MCP servers in Scala. It handles the JSON-RPC messaging and MCP
protocol details so you can focus on implementing your tools, resources, and prompts.

**Key characteristics:**

- **Protocol library, not framework** - provides protocol implementation and type-safe APIs; you bring your own HTTP
  server, logging, and configuration
- **Scala 3 + Typelevel ecosystem** - built on Cats Effect 3, fs2, http4s, and Circe
- **Type-safe** - leverages Scala 3 features for correctness without sacrificing familiarity with the MCP spec

## Documentation

Full documentation is available at **[ingarabr.github.io/scala-mcp-sdk](https://ingarabr.github.io/scala-mcp-sdk)**

## Quick Example

```scala
import cats.effect.*
import mcp.protocol.*
import mcp.server.*

type GreetInput = (name: String, formal: Option[Boolean])
given InputDef[GreetInput] = InputDef[GreetInput](
  name   = InputField[String]("Name to greet"),
  formal = InputField[Option[Boolean]]("Use formal greeting")
)

object MyServer extends IOApp.Simple:
  val greetTool = ToolDef.unstructured[IO, GreetInput](
    name = "greet",
    description = Some("Greet someone")
  ) { (input, _) =>
    IO.pure(List(Content.Text(s"Hello, ${input.name}!")))
  }

  def run: IO[Unit] =
    (for
      server <- McpServer[IO](
        info = Implementation("my-server", "1.0.0"),
        tools = List(greetTool)
      )
      transport <- StdioTransport[IO]()
      _ <- server.serve(transport)
    yield ()).useForever
```

## Modules

| Module          | Description                                                        |
|-----------------|--------------------------------------------------------------------|
| `schemas`       | Protocol types generated from MCP JSON Schema                      |
| `server`        | `McpServer` API with stdio transport                               |
| `server-http4s` | HTTP/SSE routes (provides `HttpRoutes[F]`, you provide the server) |

## Installation

```scala
// build.sbt
libraryDependencies ++= Seq(
  "io.github.ingarabr" %% "scala-mcp-server" % "<version>",
  "io.github.ingarabr" %% "scala-mcp-server-http4s" % "<version>" // for HTTP transport
)
```

## MCP Version

This library implements MCP specification version **2025-11-25**.

## Contributing

Contributions are welcome! Please see the [documentation site](https://ingarabr.github.io/scala-mcp-sdk) for
architecture overview and contribution guidelines.

## License

[MIT](LICENSE)

## Links

- [MCP Specification](https://modelcontextprotocol.io/)
- [MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk)
- [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk)
