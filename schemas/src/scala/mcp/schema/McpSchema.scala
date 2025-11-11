package mcp.schema

import io.circe.{Codec, Decoder, Encoder}
import mcp.protocol.JsonSchemaType

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
  * // Access the JSON schema (will be an ObjectSchema)
  * val jsonSchema: JsonSchemaType.ObjectSchema = summon[Schema[Input]].jsonSchema
  * }}}
  */
trait McpSchema[A] {

  /** Circe codec for encoding/decoding */
  def codec: Codec.AsObject[A]

  /** JSON schema object describing the structure (always an ObjectSchema for case classes) */
  def jsonSchema: JsonSchemaType.ObjectSchema

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

    // Extract @description annotation from the case class itself (for the root ObjectSchema)
    val classDescription = typeSymbol.annotations
      .find { annot =>
        annot.tpe.typeSymbol.name == "description"
      }
      .flatMap {
        case Apply(_, List(Literal(StringConstant(desc)))) => Some(desc)
        case _                                             => None
      }

    // Get the primary constructor parameters
    val constructor = typeSymbol.primaryConstructor
    val params = constructor.paramSymss.flatten.filter(_.isValDef)

    // Extract field information: name, type, description, and whether it's a nested case class
    val fieldData = params.map { param =>
      val name = param.name

      // Extract description from @description annotation or Scaladoc
      val docstring = {
        // Strategy 1: Look for @description annotation on the parameter
        val descriptionAnnotation = param.annotations.find { annot =>
          annot.tpe.typeSymbol.name == "description"
        }

        descriptionAnnotation match {
          case Some(annot) =>
            // Extract the description value from the annotation
            annot match {
              case Apply(_, List(Literal(StringConstant(desc)))) => desc
              case _                                             => ""
            }
          case None =>
            // Strategy 2: Try the parameter's docstring (rarely works in Scala 3)
            val paramDoc = param.docstring.filter(_.nonEmpty).map(_.trim)

            paramDoc.getOrElse {
              // Strategy 3: Look for the corresponding field in the class body using fieldMembers
              val fields = tpe.typeSymbol.fieldMembers
              val matchingField = fields.find(_.name == name)
              val fieldDoc = matchingField.flatMap(_.docstring).filter(_.nonEmpty).map(_.trim)

              fieldDoc.getOrElse {
                // Strategy 4: Look in declarations (caseFields specifically)
                val caseFields = tpe.typeSymbol.caseFields
                val matchingCaseField = caseFields.find(_.name == name)
                matchingCaseField.flatMap(_.docstring).filter(_.nonEmpty).map(_.trim).getOrElse("")
              }
            }
        }
      }

      val typeRepr = tpe.memberType(param)
      val jsonType = scalaTypeToJsonType(using q)(typeRepr)
      val isOptional = isOptionType(using q)(typeRepr)
      val isCaseClass = isCaseClassType(using q)(typeRepr)

      (name, docstring, jsonType, isOptional, isCaseClass, typeRepr)
    }

    // Generate the JSON schema
    val schemaExpr = generateJsonSchema(using q)(fieldData, classDescription)

    // Get the codec from implicit scope
    Expr.summon[Codec.AsObject[A]] match {
      case Some(codecExpr) =>
        // Generate the implementation
        '{
          new McpSchema[A] {
            // Use the summoned Codec
            val codec: Codec.AsObject[A] = $codecExpr

            // Use the macro-generated schema
            val jsonSchema: JsonSchemaType.ObjectSchema = ${ schemaExpr }
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
      fields: List[
        (String, String, String, Boolean, Boolean, q.reflect.TypeRepr)
      ], // (name, description, jsonType, isOptional, isCaseClass, typeRepr)
      classDescription: Option[String] // Description from @description on the case class itself
  ): Expr[JsonSchemaType.ObjectSchema] = {
    import q.reflect.*
    import mcp.protocol.JsonSchemaType

    // Build expressions for each field property
    val fieldExprs = fields.map { case (name, desc, jsonType, isOptional, isCaseClass, typeRepr) =>
      val nameExpr = Expr(name)
      val descExpr = if desc.nonEmpty then Expr(Some(desc)) else Expr(None: Option[String])
      val optExpr = Expr(isOptional)

      // Generate the appropriate schema type
      val schemaExpr = if isCaseClass then {
        // For case classes, try to summon their McpSchema and use its jsonSchema
        val actualType = if isOptionType(using q)(typeRepr) then {
          typeRepr match {
            case AppliedType(_, args) => args.head
            case _                    => typeRepr
          }
        } else typeRepr

        actualType.asType match {
          case '[t] =>
            Expr.summon[McpSchema[t]] match {
              case Some(schemaInstance) =>
                // If there's a description for this nested case class field, apply it to the schema
                if desc.nonEmpty then
                  '{
                    val baseSchema = $schemaInstance.jsonSchema
                    baseSchema.copy(description = Some(${ Expr(desc) })): JsonSchemaType
                  }
                else '{ $schemaInstance.jsonSchema: JsonSchemaType }
              case None =>
                // Fail compilation if no McpSchema instance found for nested case class
                val typeName = actualType.show
                report.errorAndAbort(
                  s"No McpSchema[$typeName] found in scope for field '$name'. " +
                    s"Please add 'given McpSchema[$typeName] = McpSchema.derived' to the companion object of $typeName"
                )
            }
        }
      } else {
        // For primitive types, generate the appropriate schema
        jsonType match {
          case "string" =>
            '{ JsonSchemaType.StringSchema(description = $descExpr) }
          case "number" =>
            '{ JsonSchemaType.NumberSchema(description = $descExpr) }
          case "integer" =>
            '{ JsonSchemaType.IntegerSchema(description = $descExpr) }
          case "boolean" =>
            '{ JsonSchemaType.BooleanSchema(description = $descExpr) }
          case "array" =>
            '{ JsonSchemaType.ArraySchema(description = $descExpr) }
          case _ =>
            '{ JsonSchemaType.StringSchema(description = $descExpr) }
        }
      }

      '{ ($nameExpr, $schemaExpr, $optExpr) }
    }

    val fieldsListExpr = Expr.ofList(fieldExprs)
    val classDescExpr = Expr(classDescription)

    // Build the schema at runtime
    '{
      val properties = $fieldsListExpr
      val requiredFieldsList = properties.filterNot(_._3).map(_._1)
      val propsMap: Map[String, JsonSchemaType] = properties.map { case (name, schema, _) => name -> schema }.toMap

      JsonSchemaType.ObjectSchema(
        properties = Some(propsMap),
        required = if requiredFieldsList.nonEmpty then Some(requiredFieldsList) else None,
        description = $classDescExpr
      )
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

  /** Check if a type is a case class */
  private def isCaseClassType(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*
    // First unwrap Option if present
    val actualType = tpe.dealias match {
      case AppliedType(tycon, args) if tycon =:= TypeRepr.of[Option] => args.head.dealias
      case other                                                     => other
    }

    actualType match {
      // Exclude primitive types
      case t if t =:= TypeRepr.of[String]  => false
      case t if t =:= TypeRepr.of[Int]     => false
      case t if t =:= TypeRepr.of[Long]    => false
      case t if t =:= TypeRepr.of[Double]  => false
      case t if t =:= TypeRepr.of[Float]   => false
      case t if t =:= TypeRepr.of[Boolean] => false
      case t if isListType(using q)(t)     => false
      case other                           =>
        // Check if it's a case class
        other.typeSymbol.flags.is(Flags.Case)
    }
  }
}
