package mcp.server

import io.circe.Json
import mcp.protocol.JsonSchemaType

/** A field descriptor parameterized by the field's Scala type.
  *
  * The phantom type `A` ties metadata to a specific field type, enabling compile-time verification that metadata matches the actual field
  * type. This is the unified field descriptor used across tools, elicitation, and prompts.
  *
  * @tparam A
  *   The Scala type this field describes (phantom type for compile-time safety)
  */
case class InputField[A](
    description: Option[String] = None,
    title: Option[String] = None,
    default: Option[A] = None
)(using val fieldType: InputFieldType[A]) {

  /** Generate JSON Schema for this field. */
  def toJsonSchema: JsonSchemaType = fieldType.toJsonSchema(description, title, default)

  /** Whether this field is optional (i.e. `A` is `Option[_]`). */
  def isOptional: Boolean = fieldType.isOptional

  /** Extract a value from a JSON value. */
  def extract(json: Json): Either[String, A] = fieldType.extract(json)
}

object InputField {

  /** Create with just a description. */
  def apply[A: InputFieldType](description: String): InputField[A] =
    InputField[A](description = Some(description))

  /** Create with title and description. */
  def apply[A: InputFieldType](title: String, description: String): InputField[A] =
    InputField[A](title = Some(title), description = Some(description))

  // ---------------------------------------------------------------------------
  // Array of primitives
  // ---------------------------------------------------------------------------

  /** Required array of primitives (e.g. `List[String]`). */
  def array[A: InputFieldType](description: String): InputField[List[A]] = {
    val itemType = summon[InputFieldType[A]]
    given InputFieldType[List[A]] = listFieldType[A](itemType, Some(description))
    InputField[List[A]](description = Some(description))
  }

  /** Optional array of primitives (e.g. `Option[List[String]]`). */
  def optionalArray[A: InputFieldType](description: String): InputField[Option[List[A]]] = {
    val itemType = summon[InputFieldType[A]]
    given InputFieldType[List[A]] = listFieldType[A](itemType, Some(description))
    InputField[Option[List[A]]](description = Some(description))
  }

  // ---------------------------------------------------------------------------
  // Array of objects (with nested InputDef)
  // ---------------------------------------------------------------------------

  /** Required array of objects (e.g. `List[(name: String, age: Int)]`). */
  def array[A](description: String, itemDef: InputDef[A]): InputField[List[A]] = {
    given InputFieldType[List[A]] = objectListFieldType[A](itemDef, Some(description))
    InputField[List[A]](description = Some(description))
  }

  /** Optional array of objects (e.g. `Option[List[(name: String, age: Int)]]`). */
  def optionalArray[A](description: String, itemDef: InputDef[A]): InputField[Option[List[A]]] = {
    given InputFieldType[List[A]] = objectListFieldType[A](itemDef, Some(description))
    InputField[Option[List[A]]](description = Some(description))
  }

  // ---------------------------------------------------------------------------
  // Nested objects (with InputDef)
  // ---------------------------------------------------------------------------

  /** Required nested object field. */
  def obj[A](description: String, objectDef: InputDef[A]): InputField[A] = {
    given InputFieldType[A] = objectFieldType[A](objectDef, Some(description))
    InputField[A](description = Some(description))
  }

  /** Optional nested object field. */
  def optionalObject[A](description: String, objectDef: InputDef[A]): InputField[Option[A]] = {
    given InputFieldType[A] = objectFieldType[A](objectDef, Some(description))
    InputField[Option[A]](description = Some(description))
  }

  // ---------------------------------------------------------------------------
  // Internal InputFieldType factories
  // ---------------------------------------------------------------------------

  private def listFieldType[A](itemType: InputFieldType[A], description: Option[String]): InputFieldType[List[A]] =
    new InputFieldType[List[A]] {
      def toJsonSchema(desc: Option[String], title: Option[String], default: Option[List[A]]): JsonSchemaType =
        JsonSchemaType.ArraySchema(
          items = Some(itemType.toJsonSchema(None, None, None)),
          description = desc.orElse(description),
          title = title
        )

      def extract(json: Json): Either[String, List[A]] =
        json.asArray match {
          case Some(arr) =>
            arr.toList.zipWithIndex.foldRight[Either[String, List[A]]](Right(Nil)) { case ((item, idx), acc) =>
              for {
                tail <- acc
                value <- itemType.extract(item).left.map(e => s"[$idx]: $e")
              } yield value :: tail
            }
          case None => Left("Expected array")
        }

      def isOptional: Boolean = false
    }

  private def objectListFieldType[A](itemDef: InputDef[A], description: Option[String]): InputFieldType[List[A]] =
    new InputFieldType[List[A]] {
      def toJsonSchema(desc: Option[String], title: Option[String], default: Option[List[A]]): JsonSchemaType =
        JsonSchemaType.ArraySchema(
          items = Some(itemDef.jsonSchema),
          description = desc.orElse(description),
          title = title
        )

      def extract(json: Json): Either[String, List[A]] =
        json.asArray match {
          case Some(arr) =>
            arr.toList.zipWithIndex.foldRight[Either[String, List[A]]](Right(Nil)) { case ((item, idx), acc) =>
              for {
                tail <- acc
                obj <- item.asObject.toRight(s"[$idx]: Expected object")
                value <- itemDef.extract(obj).left.map(e => s"[$idx]: $e")
              } yield value :: tail
            }
          case None => Left("Expected array")
        }

      def isOptional: Boolean = false
    }

  private def objectFieldType[A](objectDef: InputDef[A], description: Option[String]): InputFieldType[A] =
    new InputFieldType[A] {
      def toJsonSchema(desc: Option[String], title: Option[String], default: Option[A]): JsonSchemaType =
        objectDef.jsonSchema.copy(description = desc.orElse(description), title = title)

      def extract(json: Json): Either[String, A] =
        json.asObject match {
          case Some(obj) => objectDef.extract(obj)
          case None      => Left("Expected object")
        }

      def isOptional: Boolean = false
    }
}
