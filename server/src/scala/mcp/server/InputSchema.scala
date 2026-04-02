package mcp.server

import io.circe.{Decoder, Encoder}
import mcp.protocol.JsonSchemaType

/** Minimal interface providing JSON Schema and a Decoder for input types.
  *
  * This is what `ToolDef` and `PromptDef` require via `using` parameters. Satisfied by `InputDef[A]`.
  */
trait InputSchema[A] {
  def jsonSchema: JsonSchemaType.ObjectSchema
  def decoder: Decoder[A]
}

object InputSchema {

  /** InputSchema for tools that take no arguments. Produces `{ "type": "object" }`. */
  given InputSchema[Unit] with {
    def jsonSchema: JsonSchemaType.ObjectSchema = JsonSchemaType.ObjectSchema()
    def decoder: Decoder[Unit] = Decoder.const(())
  }
}

/** Minimal interface providing JSON Schema and an Encoder for output types.
  *
  * This is what `ToolDef.structured` requires via `using` parameters. Satisfied by `OutputDef[A]`.
  */
trait OutputSchema[A] {
  def jsonSchema: JsonSchemaType.ObjectSchema
  def encoder: Encoder.AsObject[A]
}
