---
sidebar_position: 5
---

# Logging

MCP defines a protocol-level logging mechanism where the server sends log messages to the client. This is separate from your application's own logging (log4cats, slf4j, etc.) — MCP logging delivers structured messages over the protocol for the client to display or act on.

See the [MCP specification on logging](https://modelcontextprotocol.io/docs/concepts/utilities/logging) for the full concept.

## Sending Log Messages

Both `ToolContext` and `ResourceContext` provide a `log` method:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Json
import mcp.protocol.{Content, LoggingLevel}
import mcp.server.*

type AnalyzeInput = (query: String, verbose: Option[Boolean])
given InputDef[AnalyzeInput] = InputDef[AnalyzeInput](
  query   = InputField[String]("Query to analyze"),
  verbose = InputField[Option[Boolean]]("Enable verbose output")
)

val analyzeTool = ToolDef.unstructured[IO, AnalyzeInput](
  name = "analyze",
  description = Some("Analyze a query")
) { (input, ctx) =>
  for {
    _ <- ctx.log(LoggingLevel.info, Json.fromString(s"Analyzing: ${input.query}"))
    _ <- ctx.log(LoggingLevel.debug, Json.obj("step" -> Json.fromString("parsing")))
    _ <- ctx.log(LoggingLevel.warning, Json.fromString("Large result set"), Some("performance"))
  } yield List(Content.Text("Done"))
}
```

The `log` method takes:

| Parameter | Type | Description |
|-----------|------|-------------|
| `level` | `LoggingLevel` | Message severity |
| `data` | `Json` | Arbitrary JSON payload |
| `logger` | `Option[String]` | Optional logger name for categorization |

## Log Levels

MCP defines eight severity levels (lowest to highest):

| Level | Use case |
|-------|----------|
| `debug` | Detailed diagnostic information |
| `info` | General operational messages |
| `notice` | Normal but noteworthy events |
| `warning` | Potential issues |
| `error` | Error conditions |
| `critical` | Critical failures |
| `alert` | Action must be taken immediately |
| `emergency` | System is unusable |

## Client-Controlled Filtering

The client sets the minimum log level via `logging/setLevel`. The server filters messages below this threshold — they are never sent over the wire.

If the client hasn't set a level, log messages are silently dropped.

## Logging in Resources

Resource handlers also have access to logging via `ResourceContext`:

```scala mdoc:compile-only
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Codec, Json}
import mcp.protocol.LoggingLevel
import mcp.server.ResourceDef

case class Config(env: String) derives Codec.AsObject

def configResource[F[_]: Async]: ResourceDef[F, Config] =
  ResourceDef[F, Config](
    uri = "config://app",
    name = "App Config",
    handler = ctx => {
      ctx.log(LoggingLevel.info, Json.fromString("Config accessed")) *>
        Async[F].pure(Config("production"))
    }
  )
```

## MCP Logging vs Application Logging

| | MCP Logging (`ctx.log`) | Application Logging (log4cats, etc.) |
|---|---|---|
| **Destination** | Sent to MCP client over the protocol | Written to stderr, files, etc. |
| **Audience** | The AI client / end user | Developers / operators |
| **Structured** | JSON payload | Framework-dependent |
| **Use for** | Tool progress, status updates, diagnostics visible to the client | Internal debugging, error tracking |

Both can coexist. Use MCP logging when you want the client to see the message, and application logging for operational concerns.

:::caution
Never include credentials, secrets, PII, or sensitive system details in MCP log messages — they are transmitted to the client.
:::
