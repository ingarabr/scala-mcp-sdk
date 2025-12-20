package mcp.server

import io.circe.Json
import mcp.protocol.{LoggingLevel, Root}

/** Execution context for resources, providing logging and roots access.
  *
  * This context allows resource handlers to:
  *   - Send log messages to the client
  *   - Access client's workspace roots for boundary validation
  *
  * Unlike ToolContext, resources don't support progress reporting (they are expected to complete quickly).
  *
  * Logging notifications are sent to the client based on the minimum log level configured via the `logging/setLevel` request. Messages
  * below the threshold are filtered by the server.
  *
  * @tparam F
  *   The effect type
  */
trait ResourceContext[F[_]] {

  /** Send a log message to the client.
    *
    * The client controls the minimum log level via the `logging/setLevel` request. Messages below the threshold are filtered by the server.
    *
    * IMPORTANT: Implementations must exclude credentials, secrets, PII, and sensitive system details from all transmitted logs.
    *
    * @param level
    *   Severity level (debug, info, notice, warning, error, critical, alert, emergency)
    * @param data
    *   Arbitrary JSON data to log
    * @param logger
    *   Optional logger name (for categorization)
    */
  def log(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): F[Unit]

  /** The list of roots exposed by the client, if available.
    *
    * Roots represent the operational boundaries (workspace directories/files) that the client has exposed to the server. Resources can use
    * this to:
    *   - Validate file operations are within allowed boundaries
    *   - Understand the workspace structure
    *   - Scope operations appropriately
    *
    * Returns None if:
    *   - Client doesn't support roots capability
    *   - Roots haven't been fetched yet (cache miss)
    *   - Server is not initialized
    */
  def roots: Option[List[Root]]
}
