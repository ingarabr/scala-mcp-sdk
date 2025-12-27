package everything.tools

import cats.effect.Async
import io.circe.Codec
import mcp.protocol.{Content, ToolAnnotations}
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

/** Echo tool - echoes back the input message.
  *
  * This is a simple stateless tool that returns the input unchanged.
  */
object EchoTool {

  @description("Input for echo operation")
  case class Input(
      @description("Message to echo back")
      message: String
  ) derives Codec.AsObject,
        McpSchema

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
