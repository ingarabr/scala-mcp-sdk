---
sidebar_position: 4
---

# Completions

Completions provide auto-complete suggestions for prompt arguments and resource template variables. When a client is
filling in arguments for a prompt or variables for a resource template URI, it can request completion suggestions.

## Basic Usage

Create a `CompletionDef` and register it with the server:

```scala mdoc:compile-only
import cats.effect.Async
import mcp.protocol.CompletionCompletion
import mcp.server.CompletionDef

val languages = List("English", "French", "German", "Spanish", "Japanese")

def languageCompletion[F[_]: Async]: CompletionDef[F] =
  CompletionDef.forPrompt[F](
    promptName = "translate",
    handler = (argName, currentValue, _) =>
      Async[F].pure {
        if argName == "language" then {
          val matches = languages.filter(_.toLowerCase.startsWith(currentValue.toLowerCase))
          CompletionCompletion(values = matches.take(10), total = Some(matches.size))
        } else CompletionCompletion(values = Nil)
      }
  )
```

## Completion for Resource Templates

Complete variables in URI templates:

```scala mdoc:compile-only
import cats.effect.Async
import mcp.protocol.CompletionCompletion
import mcp.server.{CompletionDef, UriTemplate}

val template = UriTemplate.parse("file:///{path}").toOption.get

def fileCompletion[F[_]: Async]: CompletionDef[F] =
  CompletionDef.forResourceTemplate[F](
    uriTemplate = template,
    handler = (varName, currentValue, _) =>
      Async[F].pure {
        CompletionCompletion(values = List("readme.md", "config.json").filter(_.startsWith(currentValue)))
      }
  )
```

## Multi-Argument Context

When completing one argument, the client may provide values of other arguments already filled in. This context
is available as the third parameter:

```scala mdoc:compile-only
import cats.effect.Async
import mcp.protocol.CompletionCompletion
import mcp.server.CompletionDef

def contextAwareCompletion[F[_]: Async]: CompletionDef[F] =
  CompletionDef.forPrompt[F](
    promptName = "query",
    handler = (argName, currentValue, context) =>
      Async[F].pure {
        val otherArgs = context.flatMap(_.arguments).getOrElse(Map.empty)
        argName match {
          case "column" =>
            // Use the already-selected "table" argument to filter column suggestions
            val table = otherArgs.getOrElse("table", "")
            val columns = table match {
              case "users"  => List("id", "name", "email")
              case "orders" => List("id", "user_id", "total")
              case _        => Nil
            }
            CompletionCompletion(values = columns.filter(_.startsWith(currentValue)))
          case _ =>
            CompletionCompletion(values = Nil)
        }
      }
  )
```

## Registration

Register completions alongside your other primitives:

```scala
McpServer[IO](
  info = Implementation("my-server", "1.0.0"),
  prompts = List(translatePrompt),
  completions = List(languageCompletion)
)
```

Completions can also be added dynamically:

```scala
server.addCompletions(List(newCompletion))
server.removeCompletions(List(CompletionReference.Prompt(name = "old-prompt")))
```

## Handler Signature

The handler function receives three arguments:

| Parameter      | Type                        | Description                            |
|----------------|-----------------------------|----------------------------------------|
| `argName`      | `String`                    | Name of the argument being completed   |
| `currentValue` | `String`                    | What the user has typed so far         |
| `context`      | `Option[CompletionContext]` | Other argument values already provided |

Return a `CompletionCompletion` with:

| Field     | Type              | Description                                             |
|-----------|-------------------|---------------------------------------------------------|
| `values`  | `List[String]`    | Suggested completions (max 100)                         |
| `total`   | `Option[Int]`     | Total available completions                             |
| `hasMore` | `Option[Boolean]` | Whether more completions exist beyond the returned list |
