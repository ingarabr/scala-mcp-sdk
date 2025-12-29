---
sidebar_position: 2
---

# Elicitation

Elicitation lets your server request information from the user through the client. Use it when a tool needs user input
to proceed.

See [MCP elicitation](https://modelcontextprotocol.io/docs/concepts/elicitation) for the full concept.

## Requesting User Input

Use `ctx.elicit` with a tuple of `FormField` definitions. Here's a complete example from a file deletion tool:

```scala mdoc:compile-only
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Codec
import mcp.protocol.ToolAnnotations
import mcp.schema.{McpSchema, description}
import mcp.server.*

object DeleteFileTool {

  @description("File to delete")
  case class Input(
      @description("Path to the file")
      path: String
  ) derives Codec.AsObject, McpSchema

  case class Output(deleted: Boolean, message: String) derives Codec.AsObject
  object Output {
    given McpSchema[Output] = McpSchema.derived
  }

  // Define fields as a tuple - each field extracts to its type
  private val confirmFields = (
    FormField.boolean.required("confirm", title = Some("Confirm"), description = Some("Set to true to confirm deletion")),
    FormField.oneOf.optional("reason", List("cleanup", "outdated", "duplicate", "other"), title = Some("Reason")),
    FormField.string.required("confirmedBy", title = Some("Confirmed by"), description = Some("Your name"))
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef.structured[F, Input, Output](
      name = "delete_file",
      description = Some("Delete a file (with confirmation)"),
      annotations = Some(ToolAnnotations(
        title = Some("Delete File"),
        destructiveHint = Some(true)
      ))
    ) { (input, ctx) =>
      // Check capability before calling elicit
      ctx.elicitationCapability.fold(
        notSupported = Async[F].pure(Output(false, "Elicitation not supported")),
        supported = ctx.elicit(s"Delete ${input.path}?", confirmFields).map {
          // Destructure the tuple - types match field definitions
          case ElicitResult.Accepted((confirmed, reason, confirmedBy)) =>
            if confirmed then Output(true, s"Deleted ${input.path} by $confirmedBy" + reason.fold("")(r => s" ($r)"))
            else Output(false, "User did not confirm")
          case ElicitResult.Declined  => Output(false, "User declined")
          case ElicitResult.Cancelled => Output(false, "Cancelled")
        }
      )
    }
}
```

## Response Types

| Response            | Meaning                         |
|---------------------|---------------------------------|
| `Accepted(values)`  | User submitted data (tuple)     |
| `Declined`          | User explicitly declined        |
| `Cancelled`         | User dismissed without choosing |

## Field Types

Define what input you need using `FormField`:

### String

```scala mdoc:compile-only
import mcp.server.{FormField, StringFormat}

FormField.string.required("name")
FormField.string.optional("email", format = Some(StringFormat.Email))
FormField.string.required("url", format = Some(StringFormat.Uri))
```

### Number

```scala mdoc:compile-only
import mcp.server.FormField

FormField.number.required("amount")
FormField.number.optional("threshold", minimum = Some(0.0), maximum = Some(100.0))
```

### Integer

```scala mdoc:compile-only
import mcp.server.FormField

FormField.integer.required("count")
FormField.integer.optional("limit", minimum = Some(1), maximum = Some(100))
```

### Boolean

```scala mdoc:compile-only
import mcp.server.FormField

FormField.boolean.required("confirm")
FormField.boolean.optional("sendEmail", default = Some(true))
```

### Enum (Selection)

```scala mdoc:compile-only
import mcp.server.FormField

FormField.oneOf.required("size", List("small", "medium", "large"))
FormField.oneOf.optional("priority", List("low", "normal", "high"))
```

### Multiple Fields

Combine fields in a tuple:

```scala mdoc:compile-only
import mcp.server.{FormField, StringFormat}

val fields = (
  FormField.string.required("name"),
  FormField.string.required("email", format = Some(StringFormat.Email)),
  FormField.boolean.optional("subscribe")
)
// Returns: (String, String, Option[Boolean])
```

## Out-of-Band Elicitation

For sensitive operations, direct users to a URL:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content
import mcp.server.{ElicitResult, ToolContext}

def requestOAuth(ctx: ToolContext[IO]): IO[List[Content]] = {
  ctx.elicitUrl(
    message = "Please authorize access to your account",
    url = "https://example.com/oauth/authorize?client_id=..."
  ).map {
    case ElicitResult.Accepted(_) =>
      List(Content.Text("Authorization flow started"))
    case ElicitResult.Declined | ElicitResult.Cancelled =>
      List(Content.Text("Authorization cancelled"))
  }
}
```

## Checking Client Support

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content
import mcp.server.{ElicitationCapability, ToolContext}

def checkElicitation(ctx: ToolContext[IO]): IO[List[Content]] = {
  ctx.elicitationCapability match {
    case ElicitationCapability.Supported =>
      IO.pure(List(Content.Text("Elicitation supported")))
    case ElicitationCapability.NotSupported =>
      IO.pure(List(Content.Text("Client doesn't support elicitation")))
  }
}
```

## Best Practices

- Keep requests focused - ask for one thing at a time
- Provide clear messages explaining why you need the input
- Handle all three response types appropriately
- Use `elicitUrl` for sensitive data (credentials, payments)
