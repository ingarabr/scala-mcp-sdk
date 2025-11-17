package examples

import cats.effect.Async
import org.http4s.HttpRoutes
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}

/** HTTP logging middleware with colored console output.
  *
  * Logs requests in cyan and responses in green for better visual distinction.
  */
object ColoredConsoleLogger {

  /** Apply colored logging to HTTP routes.
    *
    * @param logHeaders
    *   Whether to log HTTP headers
    * @param logBody
    *   Whether to log request/response bodies
    * @param routes
    *   The routes to apply logging to
    * @return
    *   Routes with colored logging middleware applied
    */
  def apply[F[_]: Async](
      logHeaders: Boolean = true,
      logBody: Boolean = true
  )(routes: HttpRoutes[F]): HttpRoutes[F] = {

    // ANSI color codes
    val cyan = "\u001b[36m"
    val green = "\u001b[32m"
    val reset = "\u001b[0m"

    // Colored log functions
    val requestLog = (msg: String) => Async[F].delay(println(s"$cyan$msg$reset"))
    val responseLog = (msg: String) => Async[F].delay(println(s"$green$msg$reset"))

    // Compose ResponseLogger and RequestLogger
    ResponseLogger.httpRoutes(
      logHeaders = logHeaders,
      logBody = logBody,
      logAction = Some(responseLog)
    )(
      RequestLogger.httpRoutes(
        logHeaders = logHeaders,
        logBody = logBody,
        logAction = Some(requestLog)
      )(routes)
    )
  }
}
