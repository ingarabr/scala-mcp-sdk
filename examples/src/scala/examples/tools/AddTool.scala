package examples.tools

import cats.effect.*
import io.circe.*
import mcp.schema.McpSchema
import mcp.server.ToolDef

/** Add tool - adds two numbers together.
  *
  * This demonstrates a tool with numeric input and output, using automatic schema derivation from Scaladoc comments.
  */
object AddTool {

  case class Input(
      /** First number */
      a: Double,
      /** Second number */
      b: Double
  ) derives Codec.AsObject,
        McpSchema

  case class Output(
      /** The sum of the two numbers */
      result: Double
  ) derives Codec.AsObject

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef[F, Input, Output](
      name = "add",
      description = Some("Add two numbers"),
      handler = input => Async[F].pure(Output(input.a + input.b))
    )
}
