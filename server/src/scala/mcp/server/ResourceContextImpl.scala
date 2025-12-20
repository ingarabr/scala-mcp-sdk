package mcp.server

import cats.effect.Sync
import io.circe.Json
import io.circe.syntax.*
import mcp.protocol.{Constants, JsonRpcResponse, LoggingLevel, LoggingMessageNotification, Root}

/** Sends logging notifications via the transport layer for resource handlers.
  *
  * Filters log messages based on minimum log level.
  *
  * @param transport
  *   Transport to send notifications through
  * @param minLogLevel
  *   Minimum log level to send (messages below this are filtered)
  * @param rootsList
  *   List of roots exposed by the client (if available)
  * @tparam F
  *   The effect type
  */
class ResourceContextImpl[F[_]: Sync](
    transport: Transport[F],
    minLogLevel: Option[LoggingLevel],
    rootsList: Option[List[Root]]
) extends ResourceContext[F] {

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

  def roots: Option[List[Root]] = rootsList

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

object ResourceContextImpl {

  /** Create a ResourceContext with the given configuration.
    *
    * @param transport
    *   Transport to send notifications through
    * @param minLogLevel
    *   Minimum log level to send (messages below this are filtered)
    * @param roots
    *   List of roots exposed by the client (if available)
    */
  def apply[F[_]: Sync](
      transport: Transport[F],
      minLogLevel: Option[LoggingLevel] = None,
      roots: Option[List[Root]] = None
  ): ResourceContext[F] =
    new ResourceContextImpl[F](transport, minLogLevel, roots)

  /** Create a no-op ResourceContext that ignores all logging calls.
    *
    * Useful for testing or when context capabilities are not needed.
    */
  def noop[F[_]: Sync]: ResourceContext[F] =
    new ResourceContext[F] {
      def log(level: LoggingLevel, data: Json, logger: Option[String]): F[Unit] = Sync[F].unit
      def roots: Option[List[Root]] = None
    }
}
