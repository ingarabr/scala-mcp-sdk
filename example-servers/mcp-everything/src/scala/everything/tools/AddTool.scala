package everything.tools

import cats.effect.Async
import io.circe.Codec
import mcp.protocol.{JsonSchemaType, ToolAnnotations}
import mcp.server.{InputDef, InputField, OutputDef, ToolDef}

/** Add tool - adds two numbers together.
  *
  * Demonstrates a structured tool with typed input and output.
  */
object AddTool {

  type Input = (a: Double, b: Double)
  given InputDef[Input] = InputDef[Input](
    a = InputField[Double]("First number to add"),
    b = InputField[Double]("Second number to add")
  )

  case class Output(result: Double) derives Codec.AsObject
  given OutputDef[Output] = OutputDef.raw(
    JsonSchemaType.ObjectSchema(
      properties = Some(Map("result" -> JsonSchemaType.NumberSchema(description = Some("Sum of the two numbers")))),
      required = Some(List("result"))
    ),
    summon[Codec.AsObject[Output]]
  )

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
