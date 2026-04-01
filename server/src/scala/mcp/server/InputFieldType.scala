package mcp.server

import io.circe.{Json, JsonObject}
import mcp.protocol.JsonSchemaType

/** Typeclass that maps Scala types to JSON Schema and handles value extraction.
  *
  * This is the unified typeclass for all input field types across tools, elicitation, and prompts. It provides:
  *   - JSON Schema generation for protocol-level schema descriptions
  *   - Value extraction from JSON for parsing input
  *   - Optional/required field tracking
  *
  * @tparam A
  *   The Scala type this typeclass maps
  */
trait InputFieldType[A] {

  /** Generate a JSON Schema type for this field, incorporating metadata. */
  def toJsonSchema(
      description: Option[String],
      title: Option[String],
      default: Option[A]
  ): JsonSchemaType

  /** Extract a value of type A from a JSON value. */
  def extract(json: Json): Either[String, A]

  /** Whether this field type represents an optional value. */
  def isOptional: Boolean
}

object InputFieldType {

  given InputFieldType[String] with {
    def toJsonSchema(description: Option[String], title: Option[String], default: Option[String]): JsonSchemaType =
      JsonSchemaType.StringSchema(description = description, title = title, default = default)

    def extract(json: Json): Either[String, String] =
      json.asString.toRight("Expected string")

    def isOptional: Boolean = false
  }

  given InputFieldType[Int] with {
    def toJsonSchema(description: Option[String], title: Option[String], default: Option[Int]): JsonSchemaType =
      JsonSchemaType.IntegerSchema(description = description, title = title, default = default)

    def extract(json: Json): Either[String, Int] =
      json.asNumber.flatMap(_.toInt).toRight("Expected integer")

    def isOptional: Boolean = false
  }

  given InputFieldType[Long] with {
    def toJsonSchema(description: Option[String], title: Option[String], default: Option[Long]): JsonSchemaType =
      JsonSchemaType.IntegerSchema(description = description, title = title, default = default.map(_.toInt))

    def extract(json: Json): Either[String, Long] =
      json.asNumber.flatMap(_.toLong).toRight("Expected integer")

    def isOptional: Boolean = false
  }

  given InputFieldType[Double] with {
    def toJsonSchema(description: Option[String], title: Option[String], default: Option[Double]): JsonSchemaType =
      JsonSchemaType.NumberSchema(description = description, title = title, default = default)

    def extract(json: Json): Either[String, Double] =
      json.asNumber.map(_.toDouble).toRight("Expected number")

    def isOptional: Boolean = false
  }

  given InputFieldType[Boolean] with {
    def toJsonSchema(description: Option[String], title: Option[String], default: Option[Boolean]): JsonSchemaType =
      JsonSchemaType.BooleanSchema(description = description, title = title, default = default)

    def extract(json: Json): Either[String, Boolean] =
      json.asBoolean.toRight("Expected boolean")

    def isOptional: Boolean = false
  }

  given [A](using inner: InputFieldType[A]): InputFieldType[Option[A]] with {
    def toJsonSchema(description: Option[String], title: Option[String], default: Option[Option[A]]): JsonSchemaType =
      inner.toJsonSchema(description, title, default.flatten)

    def extract(json: Json): Either[String, Option[A]] =
      if json.isNull then Right(None)
      else inner.extract(json).map(Some(_))

    def isOptional: Boolean = true
  }
}
