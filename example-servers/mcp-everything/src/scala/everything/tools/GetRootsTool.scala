package everything.tools

import cats.effect.Async
import io.circe.Decoder
import io.circe.syntax.*
import mcp.protocol.{Content, JsonSchemaType, ToolAnnotations}
import mcp.server.{InputDef, ToolDef}

/** Get roots tool - returns the list of roots exposed by the client.
  *
  * Demonstrates accessing the roots from the tool context.
  */
object GetRootsTool {

  case class Input() derives Decoder
  given InputDef[Input] = InputDef.raw(
    JsonSchemaType.ObjectSchema(),
    summon[Decoder[Input]]
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "get-roots-list",
      description = Some("Returns the list of roots exposed by the client"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Get Roots List"),
          readOnlyHint = Some(true),
          idempotentHint = Some(true),
          openWorldHint = Some(false)
        )
      )
    ) { (_, ctx) =>
      Async[F].pure {
        ctx.roots match {
          case Some(roots) =>
            List(Content.Text(s"Roots:\n${roots.asJson.spaces2}"))
          case None =>
            List(Content.Text("No roots available (client may not support roots capability)"))
        }
      }
    }
}
