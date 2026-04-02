---
sidebar_position: 6
---

# Tasks

Tasks let clients run tool calls asynchronously. Instead of waiting for a tool to finish, the client gets a task ID back immediately and can poll for status and results later. This is useful for long-running operations where the client doesn't want to block.

See [MCP tasks](https://modelcontextprotocol.io/docs/concepts/transports#tasks) for the full concept.

## How It Works

From the server author's perspective, **nothing changes about how you write tools**. Your tool handler is the same whether it runs synchronously or as a task. The library handles everything:

1. Client sends `tools/call` with a `task` parameter
2. The server creates a task and returns a `CreateTaskResult` immediately
3. Your tool handler runs in the background on a Cats Effect fiber
4. When done, the result is stored and the task status updates to `completed` (or `failed`)
5. Client polls with `tasks/get` and retrieves the result with `tasks/result`

## Enabling Tasks

Pass `tasksEnabled = true` when creating the server:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.McpServer

McpServer[IO](
  info = Implementation("my-server", "1.0.0"),
  tasksEnabled = true
)
```

This enables the task system at the server level. Individual tools then declare whether they support async execution via `taskMode`.

## Per-Tool Task Mode

Each tool declares its async execution mode using `TaskMode`:

| `TaskMode`       | Behavior | MCP spec value |
|------------------|----------|----------------|
| `SyncOnly`       | Tool must complete in the request. Task-augmented calls are rejected. | `execution.taskSupport = "forbidden"` (default) |
| `AsyncAllowed`   | Client can choose sync or async. Works both ways. | `execution.taskSupport = "optional"` |
| `AsyncOnly`      | Tool must be called as a task. Sync calls are rejected. | `execution.taskSupport = "required"` |

The default is `SyncOnly` — most tools complete quickly and don't need async support.

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content
import mcp.server.*

type DeployInput = (service: String, version: String)
given InputDef[DeployInput] = InputDef[DeployInput](
  service = InputField[String]("Service name"),
  version = InputField[String]("Version to deploy")
)

val deployTool = ToolDef.unstructured[IO, DeployInput](
  name = "deploy",
  description = Some("Deploy a service (may take several minutes)"),
  taskMode = TaskMode.AsyncAllowed
) { (input, ctx) =>
  IO.pure(List(Content.Text(s"Deployed ${input.service} v${input.version}")))
}
```

**When to use which:**

- `SyncOnly` — fast operations: lookups, calculations, simple mutations
- `AsyncAllowed` — operations that may be slow but can also complete quickly
- `AsyncOnly` — operations that always take a long time: batch processing, deployments

The library enforces these modes. A `SyncOnly` tool rejects task-augmented calls, and an `AsyncOnly` tool rejects synchronous calls. If the server doesn't have `tasksEnabled = true`, task parameters are silently ignored per the MCP spec.

## Task Configuration

Customize TTL and polling behavior:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.{McpServer, TaskConfig}

import scala.concurrent.duration.*

McpServer[IO](
  info = Implementation("my-server", "1.0.0"),
  tasksEnabled = true,
  taskConfig = TaskConfig(
    defaultTtl = 2.hours,
    defaultPollInterval = 2.seconds
  )
)
```

| Setting                  | Default  | Description                                          |
|--------------------------|----------|------------------------------------------------------|
| `defaultTtl`             | 1 hour   | How long a task lives before expiring                |
| `defaultPollInterval`    | 1 second | Suggested polling interval returned to clients       |
| `gracePeriodMultiplier`  | 10       | Tasks kept for `ttl * multiplier` after expiration   |

## Task Lifecycle

Tasks go through these statuses:

| Status      | Meaning                                |
|-------------|----------------------------------------|
| `working`   | Task is running                        |
| `completed` | Tool handler finished successfully     |
| `failed`    | Tool handler threw an error or TTL expired |
| `cancelled` | Client cancelled the task              |

The library manages all transitions automatically. Expired tasks are cleaned up lazily on the next read operation.

## Outgoing Task Support

When your server makes requests to the client (sampling, elicitation), it can also use task augmentation if the client supports it:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.McpServer

McpServer[IO](
  info = Implementation("my-server", "1.0.0"),
  tasksEnabled = true,
  useTasksForOutgoingRequests = true
)
```

When enabled, the server adds task parameters to outgoing requests and polls for completion automatically. This is transparent to your tool handler code.

## Graceful Shutdown

During shutdown, the server waits for running tasks to complete:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Implementation
import mcp.server.McpServer

import scala.concurrent.duration.*

McpServer[IO](
  info = Implementation("my-server", "1.0.0"),
  tasksEnabled = true,
  shutdownTimeout = 1.minute  // default: 30 seconds
)
```

If tasks don't complete within the timeout, their fibers are cancelled.
