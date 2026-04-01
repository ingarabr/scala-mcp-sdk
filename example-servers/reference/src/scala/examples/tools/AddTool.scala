package examples.tools

import cats.effect.*
import io.circe.*
import mcp.protocol.{JsonSchemaType, ToolAnnotations}
import mcp.server.{InputDef, InputField, OutputDef, ToolDef}

object AddTool {

  type Input = (a: Double, b: Double)
  given InputDef[Input] = InputDef[Input](
    a = InputField[Double]("First number"),
    b = InputField[Double]("Second number")
  )

  case class Output(result: Double) derives Codec.AsObject
  given OutputDef[Output] = OutputDef.raw(
    JsonSchemaType.ObjectSchema(
      properties = Some(Map("result" -> JsonSchemaType.NumberSchema(description = Some("Sum of the two numbers")))),
      required = Some(List("result"))
    ),
    summon[Encoder.AsObject[Output]]
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef.structured[F, Input, Output](
      name = "add",
      description = Some("Add two numbers"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Add Numbers"),
          readOnlyHint = Some(true), // Pure computation, no side effects
          idempotentHint = Some(true), // Same inputs always produce same output
          openWorldHint = Some(false) // Deterministic, doesn't depend on external state
        )
      )
    ) { (input, _) =>
      Async[F].pure(Output(input.a + input.b))
    }
}
