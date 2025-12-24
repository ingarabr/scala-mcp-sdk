package examples.resources

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.semiauto.*
import mcp.server.ResourceDef

import scala.concurrent.duration.*

/** A resource that returns the current timestamp.
  *
  * This resource updates every minute. Clients can subscribe to it and receive `notifications/resources/updated` when the timestamp
  * changes.
  *
  * Subscribe to updates:
  *   1. Call `resources/subscribe` with `{"uri": "timestamp://current"}`
  *   2. Receive `notifications/resources/updated` every minute
  *   3. Call `resources/read` to get the current timestamp
  */
object TimestampResource {

  val uri = "timestamp://current"

  case class TimestampData(
      timestamp: String,
      epochMillis: Long,
      message: String
  ) derives Codec.AsObject

  def apply[F[_]: Async]: ResourceDef[F, TimestampData] =
    ResourceDef[F, TimestampData](
      uri = uri,
      name = "Current Timestamp",
      description = Some("Returns the current server timestamp. Subscribe to receive updates every minute."),
      mimeType = Some("application/json"),
      handler = _ =>
        Clock[F].realTimeInstant.map { now =>
          TimestampData(
            timestamp = now.toString,
            epochMillis = now.toEpochMilli,
            message = s"Server time as of ${now}"
          )
        },
      updates = fs2.Stream.awakeEvery[F](1.minute).void
    )
}
