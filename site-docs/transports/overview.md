---
sidebar_position: 1
---

# Transport Overview

Transports handle the communication between MCP clients and your server. scala-mcp-sdk provides two transport options.

## Available Transports

| Transport                    | Use Case                    | Client Examples                    |
|------------------------------|-----------------------------|------------------------------------|
| [Stdio](./stdio.md)          | CLI tools, local processes  | Claude Desktop, VS Code extensions |
| [Streamable HTTP](./http.md) | Web services, remote access | Web apps, multi-client scenarios   |

## Choosing a Transport

### Use Stdio When

- Building a CLI tool or local integration
- The client spawns your server as a subprocess
- You want the simplest deployment (single binary)
- Single client per server instance

### Use Streamable HTTP When

- Deploying as a web service
- Multiple clients need to connect
- You need bidirectional streaming
- Integration with existing HTTP infrastructure

## Transport Architecture

Both transports implement the same `Transport[F]` trait:

```scala
trait Transport[F[_]] {
  def run(handler: RequestHandler[F]): Resource[F, Unit]
}
```

This means your `McpServer` works identically with either transport. You only change the transport layer, not your
business logic.

## Feature Differences

| Feature                 | Stdio                     | Streamable HTTP |
|-------------------------|---------------------------|-----------------|
| Multi-client            | No (1 client per process) | Yes             |
| Bidirectional streaming | N/A                       | Yes             |
| Session management      | Implicit                  | Explicit        |
| Resource subscriptions  | Limited                   | Full support    |
| Deployment              | Binary/script             | HTTP server     |

## Next Steps

- [Stdio Transport](./stdio.md) - For CLI and desktop integrations
- [Streamable HTTP Transport](./http.md) - For web services
