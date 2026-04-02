---
sidebar_position: 2
---

# Resources

Resources expose **application-driven context** to clients. They provide data like files, database schemas, or
configuration that helps language models understand your application.

Each resource is identified by a **URI** following [RFC 3986](https://datatracker.ietf.org/doc/html/rfc3986).

## Resource Definition

Each resource has:

| Field         | Required | Description                      |
|---------------|----------|----------------------------------|
| `uri`         | Yes      | Unique identifier (RFC 3986 URI) |
| `name`        | Yes      | Human-readable name              |
| `description` | No       | What the resource contains       |
| `mimeType`    | No       | Content type hint                |

## Basic Example

```scala mdoc:compile-only
import cats.effect.Async
import io.circe.Codec
import mcp.server.ResourceDef

case class ServerConfig(
    name: String,
    version: String,
    environment: String
) derives Codec.AsObject

def configResource[F[_]: Async]: ResourceDef[F, ServerConfig] =
  ResourceDef[F, ServerConfig](
    uri = "config://server.json",
    name = "Server Configuration",
    description = Some("Current server configuration"),
    mimeType = Some("application/json"),
    handler = _ => Async[F].pure(Some(ServerConfig("my-server", "1.0.0", "production")))
  )
```

Then register it with the server:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.McpServer

// McpServer[IO](
//   info = Implementation("my-server", "1.0.0"),
//   resources = List(configResource[IO])
// )
```

## URI Schemes

Use standard or custom URI schemes:

| Scheme     | Example                        | Use Case             |
|------------|--------------------------------|----------------------|
| `file://`  | `file:///project/config.json`  | Filesystem resources |
| `https://` | `https://api.example.com/data` | Web resources        |
| `git://`   | `git://repo/branch/file.txt`   | Version control      |
| Custom     | `myapp://users/123`            | Application-specific |

## Resource Annotations

Annotations help clients prioritize and filter resources:

```scala mdoc:compile-only
import cats.effect.Async
import mcp.protocol.{Annotations, Role}
import mcp.server.ResourceDef

def logsResource[F[_]: Async]: ResourceDef[F, String] =
  ResourceDef[F, String](
    uri = "logs://app/errors",
    name = "Error Logs",
    annotations = Some(Annotations(
      audience = Some(List(Role.user, Role.assistant)),
      priority = Some(0.8)
    )),
    handler = _ => Async[F].pure(Some("Error log content..."))
  )
```

| Annotation | Description                                           |
|------------|-------------------------------------------------------|
| `audience` | Who should see this: `Role.user`, `Role.assistant`, or both |
| `priority` | Importance from 0.0 to 1.0                            |

## Text vs Binary Content

The handler returns your typed data, which is automatically serialized:

```scala
// Text resource - uses Circe encoding
ResourceDef[F, Config](uri = "...", name = "...", handler = _ => Async[F].pure(Some(Config(...))))

// For binary data, set encoding to Binary
ResourceDef[F, Array[Byte]](
  uri = "...",
  name = "...",
  encoding = ResourceEncoding.Binary,
  handler = _ => Async[F].pure(Some(imageBytes))
)

// Return None to signal the resource doesn't exist (returns -32002 error)
ResourceDef[F, String](
  uri = "...",
  name = "...",
  handler = _ => Async[F].pure(None)
)
```

## Dynamic Resources

Resources and resource templates can be added or removed after the server has started:

```scala
server.addResources(List(myNewResource))
server.removeResources(List("file:///old-resource"))
server.addResourceTemplates(List(myTemplate))
server.removeResourceTemplates(List("file:///{path}"))
```

The server automatically notifies connected clients. See [Dynamic Primitives](../advanced/dynamic-primitives.md) for details.

## Security Considerations

As noted in the MCP specification:

- **Validate all URIs** - Prevent path traversal and injection attacks
- **Implement access controls** - Check permissions before returning content
- **Don't expose sensitive data** - Be intentional about what you share
