package examples.tools

import cats.effect.*
import io.circe.*
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

/** Add tool - adds two numbers together.
  *
  * This demonstrates a tool with numeric input and output, using automatic schema derivation from Scaladoc comments.
  */
object AddTool {

  @description("Input parameters for addition")
  case class Input(
      @description("First number")
      a: Double,
      @description("Second number")
      b: Double
  ) derives Codec.AsObject,
        McpSchema

  @description("Result of addition operation")
  case class Output(
      @description("Sum of the two numbers")
      result: Double
  ) derives Codec.AsObject
  case object Output {
    given McpSchema[Output] = McpSchema.derived
  }

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef.structured[F, Input, Output](
      name = "add",
      description = Some("Add two numbers")
    ) { input =>
      Async[F].pure(Output(input.a + input.b))
    }
}
