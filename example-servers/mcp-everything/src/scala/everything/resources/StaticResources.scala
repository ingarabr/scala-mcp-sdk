package everything.resources

import cats.effect.Async
import io.circe.Encoder
import mcp.protocol.Annotations
import mcp.server.{ResourceDef, ResourceEncoding}

import java.time.Instant

/** Static text resource - a simple plaintext resource with fixed content. */
object StaticTextResource {

  case class TextContent(
      message: String,
      timestamp: String
  )

  object TextContent {
    given Encoder[TextContent] = Encoder.forProduct2("message", "timestamp")(c => (c.message, c.timestamp))
  }

  def apply[F[_]: Async]: ResourceDef[F, TextContent] =
    ResourceDef[F, TextContent](
      uri = "demo://resource/static/text",
      name = "Static Text Resource",
      description = Some("A static plaintext resource for testing"),
      mimeType = Some("application/json"),
      annotations = Some(Annotations(priority = Some(0.8))),
      handler = _ =>
        Async[F].delay {
          Some(
            TextContent(
              message = "This is a static text resource from the Everything server",
              timestamp = Instant.now().toString
            )
          )
        }
    )
}

/** Static blob resource - a binary resource with base64-encoded content. */
object StaticBlobResource {

  def apply[F[_]: Async]: ResourceDef[F, Array[Byte]] =
    ResourceDef[F, Array[Byte]](
      uri = "demo://resource/static/blob",
      name = "Static Blob Resource",
      description = Some("A static binary resource for testing"),
      mimeType = Some("application/octet-stream"),
      encoding = ResourceEncoding.Binary,
      handler = _ =>
        Async[F].delay {
          val timestamp = Instant.now().toString
          Some(s"Binary content created at $timestamp".getBytes("UTF-8"))
        }
    )
}
