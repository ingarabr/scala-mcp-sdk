package mcp.server

import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import mcp.protocol.JsonSchemaType
import scala.NamedTuple.NamedTuple

/** Schema + decoder bundle for input types.
  *
  * Construction paths:
  *   - `InputDef[NamedTuple](field1 = InputField[...], ...)` — named tuple, compile-time safe
  *   - `InputDef.withDecoder[CaseClass](field1 = InputField[...], ...)` — case class with Circe Decoder
  *   - `InputDef.dynamic(List[(String, InputField[?])])` — runtime, returns `InputDef[JsonObject]`
  *   - `InputDef.raw(schema, decoder)` — escape hatch for pre-built schemas
  *
  * Designed to be used as `given` instances so ToolDef/PromptDef resolve them via `using`:
  * {{{
  * type MyInput = (name: String, age: Int)
  * given InputDef[MyInput] = InputDef[MyInput](
  *   name = InputField[String]("User name"),
  *   age  = InputField[Int]("User age")
  * )
  * }}}
  *
  * @tparam A
  *   The result type (named tuple, case class, or JsonObject for dynamic)
  */
class InputDef[A](
    val jsonSchema: JsonSchemaType.ObjectSchema,
    val extract: JsonObject => Either[String, A]
) extends InputSchema[A] {

  def decoder: Decoder[A] = Decoder.instance { cursor =>
    cursor.as[JsonObject].flatMap { obj =>
      extract(obj).left.map(e => DecodingFailure(e, cursor.history))
    }
  }
}

object InputDef {

  // ---------------------------------------------------------------------------
  // Named tuple construction (compile-time safe)
  // ---------------------------------------------------------------------------

  /** Builder for named tuple result types.
    *
    * Usage:
    * {{{
    * type Input = (name: String, age: Int)
    * val inputDef = InputDef[Input](
    *   name = InputField[String]("User name"),
    *   age  = InputField[Int]("User age")
    * )
    * }}}
    */
  class Builder[A] {
    inline def apply[N <: Tuple, MetaV <: Tuple](
        meta: NamedTuple[N, MetaV]
    ): InputDef[A] = {
      val names = InputFields.fieldNames[N]
      val schema = InputFields.toObjectSchema(names, meta)
      new InputDef[A](
        schema,
        json => InputFields.extractAll[Tuple](names, meta.toTuple, json).map(_.asInstanceOf[A])
      )
    }
  }

  /** Create an InputDef for a named tuple result type. */
  def apply[A]: Builder[A] = new Builder[A]

  // ---------------------------------------------------------------------------
  // Case class construction (with Circe Decoder)
  // ---------------------------------------------------------------------------

  /** Builder for case class result types with a Circe Decoder. */
  class DecoderBuilder[A](using decoder: Decoder[A]) {
    inline def apply[N <: Tuple, MetaV <: Tuple](
        meta: NamedTuple[N, MetaV]
    ): InputDef[A] = {
      val names = InputFields.fieldNames[N]
      val schema = InputFields.toObjectSchema(names, meta)
      new InputDef[A](
        schema,
        json => Json.fromJsonObject(json).as[A](using decoder).left.map(_.getMessage)
      )
    }
  }

  /** Create an InputDef for a case class result type with a Circe Decoder. */
  def withDecoder[A: Decoder]: DecoderBuilder[A] = new DecoderBuilder[A]

  // ---------------------------------------------------------------------------
  // Dynamic/runtime construction
  // ---------------------------------------------------------------------------

  /** Create an InputDef from a runtime list of named fields. Returns `InputDef[JsonObject]`.
    *
    * Use this for tools defined from runtime information (database schemas, config, etc.).
    */
  def dynamic(fields: List[(String, InputField[?])]): InputDef[JsonObject] = {
    val properties = fields.map { case (name, field) => name -> field.toJsonSchema }.toMap
    val required = fields.collect { case (name, field) if !field.isOptional => name }
    val schema: JsonSchemaType.ObjectSchema = JsonSchemaType.ObjectSchema(
      properties = Some(properties),
      required = if required.nonEmpty then Some(required) else None
    )
    new InputDef[JsonObject](
      schema,
      json => Right(json)
    )
  }

  // ---------------------------------------------------------------------------
  // Raw/escape hatch
  // ---------------------------------------------------------------------------

  /** Create an InputDef from a pre-built schema and decoder. Escape hatch for external schemas. */
  def raw[A](schema: JsonSchemaType.ObjectSchema, decoder: Decoder[A]): InputDef[A] =
    new InputDef[A](
      schema,
      json => Json.fromJsonObject(json).as[A](using decoder).left.map(_.getMessage)
    )
}

/** Utility operations for InputField tuples. */
object InputFields {

  /** Extract field names from a named tuple type at compile time. */
  inline def fieldNames[N <: Tuple]: List[String] =
    fieldNamesImpl[N]

  private inline def fieldNamesImpl[N <: Tuple]: List[String] =
    inline scala.compiletime.erasedValue[N] match {
      case _: EmptyTuple     => Nil
      case _: (name *: rest) =>
        scala.compiletime.constValue[name].toString :: fieldNamesImpl[rest]
    }

  /** Convert a named tuple of InputField metadata to a JsonSchemaType.ObjectSchema. */
  def toObjectSchema[N <: Tuple, V <: Tuple](
      names: List[String],
      meta: NamedTuple[N, V]
  ): JsonSchemaType.ObjectSchema = {
    val metaList = meta.toTuple.productIterator.toList.asInstanceOf[List[InputField[?]]]
    val properties = names.zip(metaList).map { case (name, field) => name -> field.toJsonSchema }.toMap
    val requiredFields = names.zip(metaList).collect {
      case (name, field) if !field.isOptional => name
    }

    JsonSchemaType.ObjectSchema(
      properties = Some(properties),
      required = if requiredFields.nonEmpty then Some(requiredFields) else None
    )
  }

  /** Extract values from JSON into a tuple matching the metadata types. */
  def extractAll[V <: Tuple](
      names: List[String],
      metaTuple: Tuple,
      json: JsonObject
  ): Either[String, V] = {
    val metaList = metaTuple.productIterator.toList.asInstanceOf[List[InputField[?]]]

    names
      .zip(metaList)
      .foldRight[Either[String, Tuple]](Right(EmptyTuple)) { case ((name, field), acc) =>
        for {
          tail <- acc
          jsonValue = json(name).getOrElse(Json.Null)
          value <- field.fieldType.asInstanceOf[InputFieldType[Any]].extract(jsonValue).left.map(e => s"$name: $e")
        } yield value *: tail
      }
      .asInstanceOf[Either[String, V]]
  }
}
