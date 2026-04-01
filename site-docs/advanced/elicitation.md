---
sidebar_position: 2
---

# Elicitation

Elicitation lets your server request information from the user through the client. Use it when a tool needs user input
to proceed.

See [MCP elicitation](https://modelcontextprotocol.io/docs/concepts/elicitation) for the full concept.

## Requesting User Input

Use `ctx.elicit` with an `InputDef` — the same type used for tool input schemas. Here's a complete example from a file
deletion tool:

```scala mdoc:compile-only
import cats.effect.Async
import io.circe.Codec
import mcp.protocol.ToolAnnotations
import mcp.server.*

object DeleteFileTool {

  type Input = (path: String, force: Option[Boolean])
  given InputDef[Input] = InputDef[Input](
    path  = InputField[String]("Path to the file"),
    force = InputField[Option[Boolean]]("Skip confirmation if true")
  )

  case class Output(deleted: Boolean, message: String) derives Codec.AsObject
  given OutputDef[Output] = OutputDef[Output](
    deleted = InputField[Boolean]("Whether the file was deleted"),
    message = InputField[String]("Result message")
  )

  // Elicit form — same InputField/InputDef pattern as tool input
  type ConfirmForm = (confirm: Boolean, reason: Option[String], confirmedBy: String)
  private val confirmDef = InputDef[ConfirmForm](
    confirm     = InputField[Boolean](title = Some("Confirm"), description = Some("Set to true to confirm deletion")),
    reason      = InputField[Option[String]](title = Some("Reason")),
    confirmedBy = InputField[String](title = Some("Confirmed by"), description = Some("Your name"))
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
      if input.force.getOrElse(false) then Async[F].pure(Output(true, s"Force-deleted ${input.path}"))
      else
        ctx.elicitationCapability.fold(
          notSupported = Async[F].pure(Output(false, "Elicitation not supported")),
          supported = ctx.elicit(s"Delete ${input.path}?", confirmDef).map {
            case ElicitResult.Accepted(form) =>
              if form.confirm then Output(true, s"Deleted ${input.path} by ${form.confirmedBy}")
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
| `Accepted(values)`  | User submitted data             |
| `Declined`          | User explicitly declined        |
| `Cancelled`         | User dismissed without choosing |

## Field Types

Define fields using `InputField[A]` with `InputFieldType` instances for the supported types:

### String

```scala mdoc:compile-only
import mcp.server.InputField

InputField[String]("User name")
InputField[Option[String]]("Optional notes")
InputField[String](title = Some("Email"), description = Some("Your email address"))
```

### Number / Integer

```scala mdoc:compile-only
import mcp.server.InputField

InputField[Double]("Amount")
InputField[Int]("Count")
InputField[Option[Int]]("Optional limit")
```

### Boolean

```scala mdoc:compile-only
import mcp.server.InputField

InputField[Boolean]("Confirm action")
InputField[Option[Boolean]](title = Some("Subscribe"), description = Some("Subscribe to newsletter"))
```

### Combining Fields

Fields are combined via named tuples with `InputDef`:

```scala mdoc:compile-only
import mcp.server.*

type UserForm = (name: String, email: String, subscribe: Option[Boolean])
val formDef = InputDef[UserForm](
  name      = InputField[String]("Your name"),
  email     = InputField[String]("Email address"),
  subscribe = InputField[Option[Boolean]]("Subscribe to newsletter")
)
// Result type: (name: String, email: String, subscribe: Option[Boolean])
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
