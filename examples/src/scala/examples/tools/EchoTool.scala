package examples.tools

import cats.effect.*
import io.circe.*
import mcp.schema.McpSchema
import mcp.server.ToolDef

/** Echo tool - echoes back the input message.
  *
  * This demonstrates a simple tool with string input and output, using automatic schema derivation from Scaladoc comments.
  */
object EchoTool {

  case class Input(
      /** The message to echo back */
      message: String
  ) derives Codec.AsObject
  object Input {
    given McpSchema[Input] = McpSchema.derived[Input]
  }

  case class Output(
      /** The echoed message */
      echo: String
  ) derives Codec.AsObject

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef[F, Input, Output](
      name = "echo",
      description = Some("Echo back the input message"),
      handler = input => Async[F].pure(Output(s"Echo: ${input.message}"))
    )
}
