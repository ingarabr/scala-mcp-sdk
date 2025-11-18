package mcp.server

import io.circe.Json
import mcp.protocol.{LoggingLevel, ProgressToken}

/** Execution context for tools, providing progress and logging capabilities.
  *
  * This context allows tools to:
  *   - Report progress for long-running operations
  *   - Send log messages to the client
  *   - Access request metadata
  *
  * The context is passed to tools that use the `*WithContext` variants of tool registration. Simple tools that don't need these
  * capabilities can use the regular registration methods.
  *
  * Progress notifications require the client to provide a `progressToken` in the request metadata. If no token is provided, progress
  * reports are silently ignored (no-op).
  *
  * Logging notifications are sent to the client based on the minimum log level configured via the `logging/setLevel` request. Messages
  * below the threshold are filtered by the server.
  *
  * @tparam F
  *   The effect type
  */
trait ToolContext[F[_]] {

  /** Report progress for a long-running operation.
    *
    * The progress token must be provided by the client in request metadata (`_meta.progressToken`). If no progress token is provided, this
    * method is a no-op.
    *
    * Progress values should increase monotonically. The total is optional and can be provided if known.
    *
    * Example:
    * {{{
    * for {
    *   _ <- ctx.reportProgress(0.0, Some(100.0), Some("Starting"))
    *   _ <- processPhase1()
    *   _ <- ctx.reportProgress(33.0, Some(100.0), Some("Phase 1 complete"))
    *   _ <- processPhase2()
    *   _ <- ctx.reportProgress(66.0, Some(100.0), Some("Phase 2 complete"))
    *   result <- processPhase3()
    *   _ <- ctx.reportProgress(100.0, Some(100.0), Some("Complete"))
    * } yield result
    * }}}
    *
    * @param progress
    *   Current progress value (should increase monotonically)
    * @param total
    *   Optional total value (if known)
    * @param message
    *   Optional human-readable progress message
    */
  def reportProgress(
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): F[Unit]

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

  /** The progress token from the request, if provided by the client. */
  def progressToken: Option[ProgressToken]
}
