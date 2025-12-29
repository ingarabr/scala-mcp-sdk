---
sidebar_position: 1
---

# Tools

Tools enable LLMs to perform actions through your server. They are **model-controlled** - the language model discovers
available tools and decides when to invoke them based on context.

> For trust and safety, there should always be a human in the loop with the ability to deny tool invocations.

## Tool Definition

Each tool has:

| Field         | Required | Description                                 |
|---------------|----------|---------------------------------------------|
| `name`        | Yes      | Unique identifier                           |
| `description` | No       | Human-readable description of functionality |
| `inputSchema` | Auto     | JSON Schema derived from input type         |
| `annotations` | No       | Hints about tool behavior                   |

## Basic Example

Define an input type with schema annotations, then create a tool:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Codec
import mcp.protocol.Content
import mcp.server.ToolDef
import mcp.schema.{McpSchema, description}

@description("Input for echo operation")
case class EchoInput(
  @description("The message to echo back")
  message: String
) derives Codec.AsObject, McpSchema

val echoTool = ToolDef.unstructured[IO, EchoInput](
  name = "echo",
  description = Some("Echo back the input message")
) { (input, ctx) =>
  IO.pure(List(Content.Text(s"Echo: ${input.message}")))
}
```

Then register it with the server:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.McpServer

// McpServer[IO](
//   info = Implementation("my-server", "1.0.0"),
//   tools = List(echoTool)
// )
```

## Input Schema

Schemas are automatically derived from your input case class using `McpSchema`. Annotations provide descriptions:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Codec
import mcp.protocol.Content
import mcp.server.ToolDef
import mcp.schema.{McpSchema, description}

@description("Input for addition")
case class AddInput(
  @description("First number")
  a: Double,
  @description("Second number")
  b: Double
) derives Codec.AsObject, McpSchema

val addTool = ToolDef.unstructured[IO, AddInput](
  name = "add",
  description = Some("Add two numbers together")
) { (input, ctx) =>
  IO.pure(List(Content.Text(s"${input.a + input.b}")))
}
```

## Tool Annotations

Annotations provide hints about tool behavior to help clients build appropriate UIs:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Codec
import mcp.protocol.{Content, ToolAnnotations}
import mcp.server.ToolDef
import mcp.schema.McpSchema

case class DeleteInput(path: String) derives Codec.AsObject, McpSchema

val deleteTool = ToolDef.unstructured[IO, DeleteInput](
  name = "delete-file",
  description = Some("Permanently delete a file"),
  annotations = Some(ToolAnnotations(
    title = Some("Delete File"),
    destructiveHint = Some(true),
    readOnlyHint = Some(false),
    idempotentHint = Some(false),
    openWorldHint = Some(false)
  ))
) { (input, ctx) =>
  IO.pure(List(Content.Text(s"Deleted: ${input.path}")))
}
```

| Annotation        | Description                                      |
|-------------------|--------------------------------------------------|
| `title`           | Human-readable display name                      |
| `destructiveHint` | Whether the tool makes irreversible changes      |
| `readOnlyHint`    | Whether the tool only reads data                 |
| `idempotentHint`  | Whether repeated calls have the same effect      |
| `openWorldHint`   | Whether the tool interacts with external systems |

## Tool Results

Tools return `List[Content]`:

### Text Content

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content

IO.pure(List(Content.Text("Operation completed")))
```

### Image Content

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content

val base64Data = "..." // Base64-encoded image
IO.pure(List(Content.Image(base64Data, "image/png")))
```

### Multiple Content Items

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content

val chartData = "..."
IO.pure(List(
  Content.Text("Summary:"),
  Content.Text("Processed 42 items"),
  Content.Image(chartData, "image/png")
))
```

## Tool Context

The second parameter provides access to server capabilities:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.{Codec, Json}
import mcp.protocol.{Content, LoggingLevel}
import mcp.server.ToolDef
import mcp.schema.McpSchema

case class ProcessInput(data: String) derives Codec.AsObject, McpSchema

val processTool = ToolDef.unstructured[IO, ProcessInput](
  name = "process",
  description = Some("Process data with progress")
) { (input, ctx) =>
  for {
    _ <- ctx.log(LoggingLevel.info, Json.fromString("Starting..."))
    _ <- ctx.reportProgress(50.0, Some(100.0))
  } yield List(Content.Text(s"Processed: ${input.data}"))
}
```

| Method | Description |
|--------|-------------|
| `reportProgress(progress, total)` | Report progress (0.0 to 1.0) |
| `log(level, data)` | Send log messages to client |

## Error Handling

For business logic failures, return content indicating the error:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Codec
import mcp.protocol.Content
import mcp.server.ToolDef
import mcp.schema.McpSchema

case class DivideInput(a: Double, b: Double) derives Codec.AsObject, McpSchema

val divideTool = ToolDef.unstructured[IO, DivideInput](
  name = "divide",
  description = Some("Divide two numbers")
) { (input, ctx) =>
  if input.b == 0.0 then
    IO.pure(List(Content.Text("Error: Cannot divide by zero")))
  else
    IO.pure(List(Content.Text(s"${input.a / input.b}")))
}
```

## Security Considerations

As noted in the MCP specification:

- **Validate all inputs** - Never trust client-provided data
- **Implement access controls** - Check permissions before operations
- **Rate limit invocations** - Prevent abuse
- **Sanitize outputs** - Don't leak sensitive information
