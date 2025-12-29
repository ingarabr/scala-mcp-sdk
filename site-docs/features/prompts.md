---
sidebar_position: 3
---

# Prompts

Prompts are reusable message templates that clients can retrieve. See
the [MCP prompts documentation](https://modelcontextprotocol.io/docs/concepts/prompts) for the full concept.

## Prompt Definition

Each prompt has:

| Field         | Required | Description          |
|---------------|----------|----------------------|
| `name`        | Yes      | Unique identifier    |
| `description` | No       | What the prompt does |
| `arguments`   | No       | Expected parameters  |

## Basic Example

The simplest way is to derive arguments from `McpSchema`:

```scala mdoc:compile-only
import cats.effect.Async
import io.circe.Codec
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.schema.{McpSchema, description}
import mcp.server.PromptDef

case class GreetArgs(
  @description("Name to greet")
  name: String
) derives Codec.AsObject
object GreetArgs {
  given McpSchema[GreetArgs] = McpSchema.derived
}

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
import io.circe.Codec
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.schema.{McpSchema, description}
import mcp.server.PromptDef

case class TranslateArgs(
  @description("Text to translate")
  text: String,
  @description("Target language (e.g., Spanish, French)")
  language: String
) derives Codec.AsObject
object TranslateArgs {
  given McpSchema[TranslateArgs] = McpSchema.derived
}

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
import io.circe.Codec
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.schema.McpSchema
import mcp.server.PromptDef

case class ReviewArgs(code: String) derives Codec.AsObject
object ReviewArgs {
  given McpSchema[ReviewArgs] = McpSchema.derived
}

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

See the [MCP specification](https://modelcontextprotocol.io/docs/concepts/prompts#embedded-resource-context) for more on
embedded resources.
