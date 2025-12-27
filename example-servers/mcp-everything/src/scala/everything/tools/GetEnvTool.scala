package everything.tools

import cats.effect.Async
import io.circe.Codec
import io.circe.syntax.*
import mcp.protocol.{Content, ToolAnnotations}
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

/** Get environment variables tool.
  *
  * Returns all environment variables as a JSON object.
  */
object GetEnvTool {

  @description("No input required")
  case class Input() derives Codec.AsObject, McpSchema

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "get-env",
      description = Some("Returns all environment variables"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Get Environment Variables"),
          readOnlyHint = Some(true),
          idempotentHint = Some(true),
          openWorldHint = Some(false)
        )
      )
    ) { (_, _) =>
      Async[F].delay {
        val env = sys.env.asJson.spaces2
        List(Content.Text(s"Environment variables:\n$env"))
      }
    }
}
