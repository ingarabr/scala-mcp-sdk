package everything.tools

import cats.effect.Async
import io.circe.Decoder
import mcp.protocol.{Content, JsonSchemaType, ToolAnnotations}
import mcp.server.{InputDef, ToolDef}

/** Echo tool - echoes back the input message.
  *
  * This is a simple stateless tool that returns the input unchanged.
  */
object EchoTool {

  case class Input(message: String) derives Decoder
  given InputDef[Input] = InputDef.raw(
    JsonSchemaType.ObjectSchema(
      properties = Some(Map("message" -> JsonSchemaType.StringSchema(description = Some("Message to echo back")))),
      required = Some(List("message"))
    ),
    summon[Decoder[Input]]
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "echo",
      description = Some("Echoes back the input message"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Echo Tool"),
          readOnlyHint = Some(true),
          idempotentHint = Some(true),
          openWorldHint = Some(false)
        )
      )
    ) { (input, _) =>
      Async[F].pure(List(Content.Text(s"Echo: ${input.message}")))
    }
}
