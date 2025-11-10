package examples.resources

import cats.effect.*
import io.circe.*
import io.circe.generic.semiauto.*
import mcp.server.ResourceDef

/** Server configuration resource.
  *
  * This demonstrates a resource that returns structured JSON data.
  */
object ServerConfigResource {

  case class ServerConfig(
      name: String,
      version: String,
      environment: String
  ) derives Codec.AsObject

  def apply[F[_]: Async]: ResourceDef[F, ServerConfig] =
    ResourceDef[F, ServerConfig](
      uri = "config://server.json",
      name = "Server Configuration",
      description = Some("Current server configuration"),
      mimeType = Some("application/json"),
      handler = () =>
        Async[F].pure(
          ServerConfig(
            name = "simple-server",
            version = "1.0.0",
            environment = "development"
          )
        )
    )
}
