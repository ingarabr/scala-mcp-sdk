package mcp.schema

import io.circe.{Codec, Decoder, Encoder, JsonObject}

import scala.annotation.tailrec
import scala.deriving.Mirror
import scala.quoted.*
import scala.compiletime.{erasedValue, summonInline}

/** A type class that provides both Circe codec and JSON schema for a type.
  *
  * This combines the functionality of Circe's Codec with JSON schema generation, using Scaladoc comments on case class fields as
  * descriptions in the generated schema.
  *
  * Example usage:
  * {{{
  * case class Input(
  *   /** The message to process */ message: String,
  *   /** Maximum length allowed */ maxLength: Int
  * ) derives Schema
  *
  * // Access the codec
  * val codec: Codec[Input] = summon[Schema[Input]].codec
  *
  * // Access the JSON schema
  * val jsonSchema: JsonObject = summon[Schema[Input]].jsonSchema
  * }}}
  */
trait McpSchema[A] {

  /** Circe codec for encoding/decoding */
  def codec: Codec.AsObject[A]

  /** JSON schema object describing the structure */
  def jsonSchema: JsonObject

  /** Convenience methods for codec access */
  def encoder: Encoder.AsObject[A] = codec
  def decoder: Decoder[A] = codec
}

object McpSchema {

  /** Summoning method for cleaner access */
  def apply[A](using schema: McpSchema[A]): McpSchema[A] = schema

  /** Derive a Schema instance for a case class using macros.
    *
    * This will:
    *   - Extract scaladoc comments from case class parameters
    *   - Generate a JSON schema with field descriptions
    *   - Use the provided Codec for encoding/decoding
    */
  inline def derived[A](using Mirror.Of[A], Codec.AsObject[A]): McpSchema[A] = ${
    derivedImpl[A]
  }

  /** Macro implementation for Schema derivation */
  private def derivedImpl[A: Type](using q: Quotes): Expr[McpSchema[A]] = {
    import q.reflect.*

    val tpe = TypeRepr.of[A]
    val typeSymbol = tpe.typeSymbol

    // Ensure it's a case class
    if !typeSymbol.flags.is(Flags.Case) then {
      report.errorAndAbort(s"Schema.derived can only be used with case classes, but ${typeSymbol.name} is not a case class")
    }

    // Get the primary constructor parameters
    val constructor = typeSymbol.primaryConstructor
    val params = constructor.paramSymss.flatten.filter(_.isValDef)

    // Extract field information: name, type, and docstring
    val fieldData = params.map { param =>
      val name = param.name
      val docstring = param.docstring.getOrElse("").trim
      val typeRepr = tpe.memberType(param)
      val jsonType = scalaTypeToJsonType(using q)(typeRepr)
      val isOptional = isOptionType(using q)(typeRepr)

      (name, docstring, jsonType, isOptional)
    }

    // Generate the JSON schema
    val schemaExpr = generateJsonSchema(using q)(fieldData)

    // Get the codec from implicit scope
    Expr.summon[Codec.AsObject[A]] match {
      case Some(codecExpr) =>
        // Generate the implementation
        '{
          new McpSchema[A] {
            // Use the summoned Codec
            val codec: Codec.AsObject[A] = $codecExpr

            // Use the macro-generated schema
            val jsonSchema: JsonObject = ${ schemaExpr }
          }
        }
      case None =>
        report.errorAndAbort(s"No Codec.AsObject[${typeSymbol.name}] found in scope. Please derive it with 'derives Codec.AsObject'")
    }
  }

  /** Generate JSON schema from field information */
  private def generateJsonSchema(using
      q: Quotes
  )(
      fields: List[(String, String, String, Boolean)] // (name, description, jsonType, isOptional)
  ): Expr[JsonObject] = {
    import io.circe.Json

    // Build expressions for each field property
    val fieldExprs = fields.map { case (name, desc, jsonType, isOptional) =>
      val nameExpr = Expr(name)
      val typeExpr = Expr(jsonType)
      val descExpr = Expr(desc)
      val optExpr = Expr(isOptional)

      if desc.nonEmpty then {
        '{ ($nameExpr, Json.obj("type" -> Json.fromString($typeExpr), "description" -> Json.fromString($descExpr)), $optExpr) }
      } else {
        '{ ($nameExpr, Json.obj("type" -> Json.fromString($typeExpr)), $optExpr) }
      }
    }

    val fieldsListExpr = Expr.ofList(fieldExprs)

    // Build the schema at runtime
    '{
      val properties = $fieldsListExpr
      val requiredFields = properties.filterNot(_._3).map(p => Json.fromString(p._1))
      val propsMap = properties.map { case (name, obj, _) => name -> obj }

      val schemaJson = if requiredFields.nonEmpty then {
        Json.obj(
          "type" -> Json.fromString("object"),
          "properties" -> Json.obj(propsMap*),
          "required" -> Json.arr(requiredFields*)
        )
      } else {
        Json.obj(
          "type" -> Json.fromString("object"),
          "properties" -> Json.obj(propsMap*)
        )
      }

      schemaJson.asObject.get
    }
  }

  /** Map Scala types to JSON schema types */
  @tailrec
  private def scalaTypeToJsonType(using q: Quotes)(tpe: q.reflect.TypeRepr): String = {
    import q.reflect.*

    tpe.dealias match {
      case t if t =:= TypeRepr.of[String]  => "string"
      case t if t =:= TypeRepr.of[Int]     => "integer"
      case t if t =:= TypeRepr.of[Long]    => "integer"
      case t if t =:= TypeRepr.of[Double]  => "number"
      case t if t =:= TypeRepr.of[Float]   => "number"
      case t if t =:= TypeRepr.of[Boolean] => "boolean"
      case t if isOptionType(using q)(t)   =>
        // For Option types, unwrap and get the inner type
        t match {
          case AppliedType(_, args) => scalaTypeToJsonType(using q)(args.head)
          case _                    => "string" // fallback
        }
      case t if isListType(using q)(t) => "array"
      case _                           => "string" // fallback for unknown types
    }
  }

  /** Check if a type is Option[T] */
  private def isOptionType(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*
    tpe.dealias match {
      case AppliedType(tycon, _) => tycon =:= TypeRepr.of[Option]
      case _                     => false
    }
  }

  /** Check if a type is List[T] or Seq[T] */
  private def isListType(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*
    tpe.dealias match {
      case AppliedType(tycon, _) =>
        tycon =:= TypeRepr.of[List] || tycon =:= TypeRepr.of[Seq]
      case _ => false
    }
  }
}
