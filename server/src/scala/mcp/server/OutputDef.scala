package mcp.server

import io.circe.Encoder
import mcp.protocol.JsonSchemaType
import scala.NamedTuple.NamedTuple

/** Schema + encoder bundle for structured output types.
  *
  * The output type must have an `Encoder.AsObject` for JSON serialization. The field metadata provides schema descriptions for the
  * protocol.
  *
  * Usage:
  * {{{
  * case class Output(result: Double) derives Codec.AsObject
  * given OutputDef[Output] = OutputDef[Output](
  *   result = InputField[Double]("Sum of the two numbers")
  * )
  * }}}
  *
  * @tparam A
  *   The output type (must have Encoder.AsObject)
  */
class OutputDef[A](
    val jsonSchema: JsonSchemaType.ObjectSchema,
    val encoder: Encoder.AsObject[A]
) extends OutputSchema[A]

object OutputDef {

  /** Builder for output types with named tuple metadata. */
  class Builder[A](using enc: Encoder.AsObject[A]) {
    inline def apply[N <: Tuple, MetaV <: Tuple](
        meta: NamedTuple[N, MetaV]
    ): OutputDef[A] = {
      val names = InputFields.fieldNames[N]
      val schema = InputFields.toObjectSchema(names, meta)
      new OutputDef[A](schema, enc)
    }
  }

  /** Create an OutputDef for a type with `Encoder.AsObject`. */
  def apply[A: Encoder.AsObject]: Builder[A] = new Builder[A]

  /** Create an OutputDef from a pre-built schema and encoder. */
  def raw[A](schema: JsonSchemaType.ObjectSchema, encoder: Encoder.AsObject[A]): OutputDef[A] =
    new OutputDef[A](schema, encoder)
}
