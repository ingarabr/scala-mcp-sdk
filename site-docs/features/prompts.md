---
sidebar_position: 3
---

# Prompts

Prompts are reusable message templates that clients can retrieve. See
the [MCP prompts documentation](https://modelcontextprotocol.io/docs/concepts/prompts) for the full concept.

## Prompt Definition

Each prompt has:

| Field         | Required | Description                                              |
|---------------|----------|----------------------------------------------------------|
| `name`        | Yes      | Unique identifier                                        |
| `description` | No       | What the prompt does                                     |
| `title`       | No       | Display title (if not provided, `name` is used)          |
| `arguments`   | No       | Expected parameters                                     |

## Basic Example

Define arguments with `InputDef` and use `PromptDef.derived`:

```scala mdoc:compile-only
import cats.effect.Async
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.server.*

type GreetArgs = (name: String, formal: Option[Boolean])
given InputDef[GreetArgs] = InputDef[GreetArgs](
  name   = InputField[String]("Name to greet"),
  formal = InputField[Option[Boolean]]("Use formal greeting")
)

def greetingPrompt[F[_]: Async]: PromptDef[F, GreetArgs] =
  PromptDef.derived[F, GreetArgs](
    name = "greeting",
    description = Some("A friendly greeting")
  ) { args =>
    Async[F].pure(List(
      PromptMessage(Role.user, Content.Text(s"Hello, ${args.name}! How can I help you today?"))
    ))
  }
```

## Prompts with Multiple Arguments

```scala mdoc:compile-only
import cats.effect.Async
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.server.*

type TranslateArgs = (text: String, language: String)
given InputDef[TranslateArgs] = InputDef[TranslateArgs](
  text     = InputField[String]("Text to translate"),
  language = InputField[String]("Target language (e.g., Spanish, French)")
)

def translatePrompt[F[_]: Async]: PromptDef[F, TranslateArgs] =
  PromptDef.derived[F, TranslateArgs](
    name = "translate",
    description = Some("Translate text to another language")
  ) { args =>
    Async[F].pure(List(
      PromptMessage(Role.user, Content.Text(s"Translate the following to ${args.language}:\n\n${args.text}"))
    ))
  }
```

## Multi-Message Prompts

Return multiple messages to set up a conversation:

```scala mdoc:compile-only
import cats.effect.Async
import io.circe.Decoder
import mcp.protocol.{Content, JsonSchemaType, PromptMessage, Role}
import mcp.server.*

case class ReviewArgs(code: String) derives Decoder
given InputDef[ReviewArgs] = InputDef.raw(
  JsonSchemaType.ObjectSchema(
    properties = Some(Map("code" -> JsonSchemaType.StringSchema(description = Some("Code to review")))),
    required = Some(List("code"))
  ),
  summon[Decoder[ReviewArgs]]
)

def reviewPrompt[F[_]: Async]: PromptDef[F, ReviewArgs] =
  PromptDef.derived[F, ReviewArgs](
    name = "code-review",
    description = Some("Code review setup")
  ) { args =>
    Async[F].pure(List(
      PromptMessage(Role.assistant, Content.Text("You are a code reviewer. Be constructive.")),
      PromptMessage(Role.user, Content.Text(s"Review the following code:\n\n${args.code}"))
    ))
  }
```

## Message Roles

| Role        | Usage                          |
|-------------|--------------------------------|
| `user`      | User input                     |
| `assistant` | Pre-filled assistant responses |

## Manual Argument Definition

For fine-grained control, define arguments manually:

```scala mdoc:compile-only
import cats.effect.Async
import io.circe.Codec
import mcp.protocol.{Content, PromptArgument, PromptMessage, Role}
import mcp.server.PromptDef

case class Args(name: String) derives Codec.AsObject

def manualPrompt[F[_]: Async]: PromptDef[F, Args] =
  PromptDef[F, Args](
    name = "greeting",
    description = Some("A friendly greeting"),
    arguments = List(
      PromptArgument(
        name = "name",
        description = Some("The name to greet"),
        required = Some(true)
      )
    ),
    handler = args => Async[F].pure(List(
      PromptMessage(Role.user, Content.Text(s"Hello, ${args.name}!"))
    ))
  )
```

## Registering Prompts

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.McpServer

// McpServer[IO](
//   info = Implementation("my-server", "1.0.0"),
//   prompts = List(greetingPrompt[IO], translatePrompt[IO])
// )
```

## Dynamic Prompts

Prompts can be added or removed after the server has started:

```scala
server.addPrompts(List(myNewPrompt))
server.removePrompts(List("old-prompt"))
```

The server automatically notifies connected clients. See [Dynamic Primitives](../advanced/dynamic-primitives.md) for details.

See the [MCP specification](https://modelcontextprotocol.io/docs/concepts/prompts#embedded-resource-context) for more on
embedded resources.
