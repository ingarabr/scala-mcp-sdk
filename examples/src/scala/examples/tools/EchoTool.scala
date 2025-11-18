package examples.tools

import cats.effect.*
import io.circe.*
import mcp.protocol.Content
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

/** Echo tool - echoes back the input message.
  *
  * This demonstrates a simple tool with string input and output, using automatic schema derivation from Scaladoc comments.
  */
object EchoTool {

  @description("Input for echo operation")
  case class Input(
      @description("The message to echo back")
      message: String
  ) derives Codec.AsObject
  object Input {
    given McpSchema[Input] = McpSchema.derived
  }

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "echo",
      description = Some("Echo back the input message")
    ) { (input, _) =>
      Async[F].pure(List(Content.Text(s"Echo: ${input.message}")))
    }
}
