package mcp.server

import cats.effect.Sync
import io.circe.Json
import io.circe.syntax.*
import mcp.protocol.{Constants, JsonRpcResponse, LoggingLevel, LoggingMessageNotification, ProgressNotification, ProgressToken}

/** Sends progress and logging notifications via the transport layer. Filters log messages based on minimum log level.
  *
  * @param transport
  *   Transport to send notifications through
  * @param token
  *   Progress token from request metadata (if provided)
  * @param minLogLevel
  *   Minimum log level to send (messages below this are filtered)
  * @tparam F
  *   The effect type
  */
class ToolContextImpl[F[_]: Sync](
    transport: Transport[F],
    token: Option[ProgressToken],
    minLogLevel: Option[LoggingLevel]
) extends ToolContext[F] {

  def reportProgress(
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): F[Unit] =
    token match {
      case Some(progressToken) =>
        val progressNotif = ProgressNotification(
          progressToken = progressToken,
          progress = progress,
          total = total,
          message = message
        )
        val notification = JsonRpcResponse.Notification(
          jsonrpc = Constants.JSONRPC_VERSION,
          method = "notifications/progress",
          params = Some(progressNotif.asJsonObject)
        )
        transport.send(notification)

      case None =>
        // No progress token provided, silently ignore
        Sync[F].unit
    }

  def log(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): F[Unit] =
    if shouldLog(level) then {
      val logNotif = LoggingMessageNotification(
        level = level,
        logger = logger,
        data = data
      )
      val notification = JsonRpcResponse.Notification(
        jsonrpc = Constants.JSONRPC_VERSION,
        method = "notifications/message",
        params = Some(logNotif.asJsonObject)
      )
      transport.send(notification)
    } else {
      // Below minimum log level, silently ignore
      Sync[F].unit
    }

  def progressToken: Option[ProgressToken] = token

  /** Check if a log message should be sent based on minimum log level.
    *
    * Log levels (from lowest to highest severity): debug, info, notice, warning, error, critical, alert, emergency
    */
  private def shouldLog(level: LoggingLevel): Boolean =
    minLogLevel match {
      case None           => true // No minimum level configured, send all logs
      case Some(minLevel) => level.ordinal >= minLevel.ordinal // Compare log levels (higher ordinal = higher severity)
    }
}

object ToolContextImpl {

  /** Create a ToolContext with the given configuration.
    *
    * @param transport
    *   Transport to send notifications through
    * @param progressToken
    *   Progress token from request metadata (if provided)
    * @param minLogLevel
    *   Minimum log level to send (messages below this are filtered)
    */
  def apply[F[_]: Sync](
      transport: Transport[F],
      progressToken: Option[ProgressToken],
      minLogLevel: Option[LoggingLevel] = None
  ): ToolContext[F] =
    new ToolContextImpl[F](transport, progressToken, minLogLevel)

  /** Create a no-op ToolContext that ignores all progress and logging calls.
    *
    * Useful for testing or when context capabilities are not needed.
    */
  def noop[F[_]: Sync]: ToolContext[F] =
    new ToolContext[F] {
      def reportProgress(progress: Double, total: Option[Double], message: Option[String]): F[Unit] = Sync[F].unit
      def log(level: LoggingLevel, data: Json, logger: Option[String]): F[Unit] = Sync[F].unit
      def progressToken: Option[ProgressToken] = None
    }
}
