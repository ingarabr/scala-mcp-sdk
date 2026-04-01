---
sidebar_position: 4
---

# Dynamic Primitives

Tools, resources, and prompts can be added or removed after the server has started. The server automatically notifies
connected clients when lists change, per the MCP specification's `listChanged` capability.

## Adding and Removing Tools

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.*
import mcp.server.*

type SearchInput = (query: String, limit: Option[Int])
given InputDef[SearchInput] = InputDef[SearchInput](
  query = InputField[String]("Search query"),
  limit = InputField[Option[Int]]("Max results")
)

val searchTool = ToolDef.unstructured[IO, SearchInput](
  name = "search",
  description = Some("Search documents")
) { (input, _) =>
  IO.pure(List(Content.Text(s"Results for: ${input.query}")))
}

// After server is created and serving:
// server.addTools(List(searchTool))       // adds tool, notifies client
// server.removeTools(List("search"))      // removes tool, notifies client
```

When tools change, the server sends `notifications/tools/list_changed`. The client re-fetches via `tools/list`.

## Adding and Removing Resources

```scala
// server.addResources(List(myResource))
// server.removeResources(List("file:///config.json"))
```

Resource templates work the same way:

```scala
// server.addResourceTemplates(List(myTemplate))
// server.removeResourceTemplates(List("file:///{path}"))
```

Both trigger `notifications/resources/list_changed`.

## Adding and Removing Prompts

```scala
// server.addPrompts(List(myPrompt))
// server.removePrompts(List("greeting"))
```

Triggers `notifications/prompts/list_changed`.

## Completions

Completion providers can also be added and removed dynamically:

```scala
// server.addCompletions(List(myCompletion))
// server.removeCompletions(List(CompletionReference.Prompt(name = "translate")))
```

## How It Works

- All primitive lists are stored in atomic references (`Ref` from Cats Effect)
- Each `add`/`remove` call atomically updates the list and sends the appropriate notification
- The server always declares `listChanged: true` for tools, resources, and prompts, even if the initial lists are empty
- Clients that support `listChanged` will re-fetch after receiving a notification

## Use Cases

- **Plugin systems** — load tools from plugins discovered at runtime
- **Feature flags** — enable/disable tools based on configuration changes
- **Multi-tenant** — adjust available tools per connection or user
- **Database-driven** — create tools from database schemas that may change
