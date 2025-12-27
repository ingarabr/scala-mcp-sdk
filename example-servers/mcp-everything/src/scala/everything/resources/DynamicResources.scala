package everything.resources

import cats.effect.Async
import io.circe.Encoder
import mcp.server.{ResourceContext, ResourceDef, ResourceEncoding, ResourceTemplateDef}

import java.time.Instant

/** Dynamic text resource template - creates text resources based on URI parameters. */
object DynamicTextResourceTemplate {

  case class DynamicTextContent(
      resourceId: Int,
      message: String,
      timestamp: String
  )

  object DynamicTextContent {
    given Encoder[DynamicTextContent] =
      Encoder.forProduct3("resourceId", "message", "timestamp")(c => (c.resourceId, c.message, c.timestamp))
  }

  def apply[F[_]: Async]: ResourceTemplateDef[F] =
    ResourceTemplateDef[F](
      uriTemplate = "demo://resource/dynamic/text/{resourceId}",
      name = "Dynamic Text Resource",
      description = Some("Plaintext dynamic resource fabricated from the {resourceId} variable, which must be a positive integer"),
      mimeType = Some("text/plain"),
      resolver = (params, _) => resolveTextResource[F](params)
    )

  private def resolveTextResource[F[_]: Async](params: Map[String, String]): F[Option[ResourceDef[F, ?]]] =
    Async[F].pure {
      for {
        idStr <- params.get("resourceId")
        id <- idStr.toIntOption
        if id > 0
      } yield ResourceDef[F, DynamicTextContent](
        uri = s"demo://resource/dynamic/text/$id",
        name = s"Dynamic Text Resource $id",
        mimeType = Some("application/json"),
        handler = _ =>
          Async[F].delay {
            DynamicTextContent(
              resourceId = id,
              message = s"This is dynamic text resource $id",
              timestamp = Instant.now().toString
            )
          }
      )
    }
}

/** Dynamic blob resource template - creates binary resources based on URI parameters. */
object DynamicBlobResourceTemplate {

  def apply[F[_]: Async]: ResourceTemplateDef[F] =
    ResourceTemplateDef[F](
      uriTemplate = "demo://resource/dynamic/blob/{resourceId}",
      name = "Dynamic Blob Resource",
      description = Some("Binary (base64) dynamic resource fabricated from the {resourceId} variable, which must be a positive integer"),
      mimeType = Some("application/octet-stream"),
      resolver = (params, _) => resolveBlobResource[F](params)
    )

  private def resolveBlobResource[F[_]: Async](params: Map[String, String]): F[Option[ResourceDef[F, ?]]] =
    Async[F].pure {
      for {
        idStr <- params.get("resourceId")
        id <- idStr.toIntOption
        if id > 0
      } yield ResourceDef[F, Array[Byte]](
        uri = s"demo://resource/dynamic/blob/$id",
        name = s"Dynamic Blob Resource $id",
        mimeType = Some("application/octet-stream"),
        encoding = ResourceEncoding.Binary,
        handler = _ =>
          Async[F].delay {
            val timestamp = Instant.now().toString
            s"Resource $id: This is a base64 blob created at $timestamp".getBytes("UTF-8")
          }
      )
    }
}
