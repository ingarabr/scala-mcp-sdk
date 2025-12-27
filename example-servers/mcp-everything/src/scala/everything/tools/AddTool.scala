package everything.tools

import cats.effect.Async
import io.circe.Codec
import mcp.protocol.ToolAnnotations
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

/** Add tool - adds two numbers together.
  *
  * Demonstrates a structured tool with typed input and output.
  */
object AddTool {

  @description("Input for addition operation")
  case class Input(
      @description("First number to add")
      a: Double,
      @description("Second number to add")
      b: Double
  ) derives Codec.AsObject,
        McpSchema

  @description("Result of addition")
  case class Output(
      @description("Sum of the two numbers")
      result: Double
  ) derives Codec.AsObject

  object Output {
    given McpSchema[Output] = McpSchema.derived
  }

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef.structured[F, Input, Output](
      name = "add",
      description = Some("Adds two numbers together"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Add Numbers"),
          readOnlyHint = Some(true),
          idempotentHint = Some(true),
          openWorldHint = Some(false)
        )
      )
    ) { (input, _) =>
      Async[F].pure(Output(input.a + input.b))
    }
}
