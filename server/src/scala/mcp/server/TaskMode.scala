package mcp.server

import mcp.protocol.{TaskSupport, ToolExecution}

/** Controls whether a tool supports asynchronous (task-based) execution.
  *
  * Maps to the MCP spec's `execution.taskSupport` field on Tool definitions:
  *   - `SyncOnly` → `forbidden` (default, omitted from wire format)
  *   - `AsyncAllowed` → `optional`
  *   - `AsyncOnly` → `required`
  *
  * @see
  *   [[https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks MCP Tasks Specification]]
  */
enum TaskMode {

  /** Tool must complete within the request. Task-augmented calls are rejected. This is the default. */
  case SyncOnly

  /** Tool supports both synchronous and task-augmented execution. The client can choose. */
  case AsyncAllowed

  /** Tool must be called with task augmentation. Synchronous calls are rejected. */
  case AsyncOnly
}

object TaskMode {

  extension (mode: TaskMode) {

    /** Convert to the protocol-level ToolExecution for the wire format. Returns None for SyncOnly (the spec default). */
    def toExecution: Option[ToolExecution] = mode match {
      case SyncOnly     => None
      case AsyncAllowed => Some(ToolExecution(taskSupport = Some(TaskSupport.optional)))
      case AsyncOnly    => Some(ToolExecution(taskSupport = Some(TaskSupport.required)))
    }
  }
}
