---
sidebar_position: 3
---

# Roots

Roots let your server discover which directories or URIs the client has exposed. Use this to understand what filesystem
areas your server can access.

See [MCP roots](https://modelcontextprotocol.io/docs/concepts/roots) for the full concept.

## Accessing Roots

Roots are available directly on the tool context:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content
import mcp.server.ToolContext

def listProjects(ctx: ToolContext[IO]): IO[List[Content]] = {
  ctx.roots match {
    case Some(roots) =>
      val projects = roots.map(r => s"${r.name.getOrElse("unnamed")}: ${r.uri}")
      IO.pure(List(Content.Text(projects.mkString("\n"))))
    case None =>
      IO.pure(List(Content.Text("No roots available")))
  }
}
```

## Root Structure

Each root has:

| Field  | Description                                      |
|--------|--------------------------------------------------|
| `uri`  | The root URI (e.g., `file:///home/user/project`) |
| `name` | Optional human-readable name                     |

## Use Cases

- **File-based tools** - Know which directories to search
- **Project discovery** - Find available workspaces
- **Scope limiting** - Restrict operations to exposed roots

## Example: Validating File Access

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content
import mcp.server.ToolContext

def readFile(ctx: ToolContext[IO], path: String): IO[List[Content]] = {
  val isAllowed = ctx.roots.exists(_.exists(root => path.startsWith(root.uri)))

  if isAllowed then
    IO.pure(List(Content.Text(s"Reading: $path")))
  else
    IO.pure(List(Content.Text(s"Access denied: $path is outside allowed roots")))
}
```
