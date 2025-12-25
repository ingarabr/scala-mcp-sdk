package mcp.server

import io.circe.{Json, JsonObject}

enum StringFormat(val value: String) {
  case Email extends StringFormat("email")
  case Uri extends StringFormat("uri")
  case Date extends StringFormat("date")
  case DateTime extends StringFormat("date-time")
}

sealed trait FormField[A] {
  def name: String
  def isRequired: Boolean
  def toJsonSchema: JsonObject
  def extract(json: JsonObject): Either[String, A]
}

object FormField {

  private def build(base: (String, Json)*)(extras: (String, Option[Json])*): JsonObject =
    extras.foldLeft(JsonObject.fromIterable(base)) { case (obj, (key, maybeValue)) =>
      maybeValue.fold(obj)(v => obj.add(key, v))
    }

  // ============================================================================
  // String fields
  // ============================================================================

  case class RequiredString(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      minLength: Option[Int] = None,
      maxLength: Option[Int] = None,
      format: Option[StringFormat] = None,
      default: Option[String] = None
  ) extends FormField[String] {
    def isRequired: Boolean = true

    def extract(json: JsonObject): Either[String, String] =
      json(name).flatMap(_.asString).toRight(s"Missing or invalid field: $name")

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("string"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "minLength" -> minLength.map(Json.fromInt),
      "maxLength" -> maxLength.map(Json.fromInt),
      "format" -> format.map(f => Json.fromString(f.value)),
      "default" -> default.map(Json.fromString)
    )
  }

  case class OptionalString(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      minLength: Option[Int] = None,
      maxLength: Option[Int] = None,
      format: Option[StringFormat] = None,
      default: Option[String] = None
  ) extends FormField[Option[String]] {
    def isRequired: Boolean = false

    def extract(json: JsonObject): Either[String, Option[String]] =
      Right(json(name).flatMap(_.asString))

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("string"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "minLength" -> minLength.map(Json.fromInt),
      "maxLength" -> maxLength.map(Json.fromInt),
      "format" -> format.map(f => Json.fromString(f.value)),
      "default" -> default.map(Json.fromString)
    )
  }

  object string {
    def required(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        minLength: Option[Int] = None,
        maxLength: Option[Int] = None,
        format: Option[StringFormat] = None,
        default: Option[String] = None
    ): RequiredString = RequiredString(name, title, description, minLength, maxLength, format, default)

    def optional(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        minLength: Option[Int] = None,
        maxLength: Option[Int] = None,
        format: Option[StringFormat] = None,
        default: Option[String] = None
    ): OptionalString = OptionalString(name, title, description, minLength, maxLength, format, default)
  }

  // ============================================================================
  // Number fields
  // ============================================================================

  case class RequiredNumber(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None,
      default: Option[Double] = None
  ) extends FormField[Double] {
    def isRequired: Boolean = true

    def extract(json: JsonObject): Either[String, Double] =
      json(name).flatMap(_.asNumber).map(_.toDouble).toRight(s"Missing or invalid field: $name")

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("number"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "minimum" -> minimum.flatMap(Json.fromDouble),
      "maximum" -> maximum.flatMap(Json.fromDouble),
      "default" -> default.flatMap(Json.fromDouble)
    )
  }

  case class OptionalNumber(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None,
      default: Option[Double] = None
  ) extends FormField[Option[Double]] {
    def isRequired: Boolean = false

    def extract(json: JsonObject): Either[String, Option[Double]] =
      Right(json(name).flatMap(_.asNumber).map(_.toDouble))

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("number"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "minimum" -> minimum.flatMap(Json.fromDouble),
      "maximum" -> maximum.flatMap(Json.fromDouble),
      "default" -> default.flatMap(Json.fromDouble)
    )
  }

  object number {
    def required(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        minimum: Option[Double] = None,
        maximum: Option[Double] = None,
        default: Option[Double] = None
    ): RequiredNumber = RequiredNumber(name, title, description, minimum, maximum, default)

    def optional(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        minimum: Option[Double] = None,
        maximum: Option[Double] = None,
        default: Option[Double] = None
    ): OptionalNumber = OptionalNumber(name, title, description, minimum, maximum, default)
  }

  // ============================================================================
  // Integer fields
  // ============================================================================

  case class RequiredInteger(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      minimum: Option[Int] = None,
      maximum: Option[Int] = None,
      default: Option[Int] = None
  ) extends FormField[Int] {
    def isRequired: Boolean = true

    def extract(json: JsonObject): Either[String, Int] =
      json(name).flatMap(_.asNumber).flatMap(_.toInt).toRight(s"Missing or invalid field: $name")

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("integer"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "minimum" -> minimum.map(Json.fromInt),
      "maximum" -> maximum.map(Json.fromInt),
      "default" -> default.map(Json.fromInt)
    )
  }

  case class OptionalInteger(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      minimum: Option[Int] = None,
      maximum: Option[Int] = None,
      default: Option[Int] = None
  ) extends FormField[Option[Int]] {
    def isRequired: Boolean = false

    def extract(json: JsonObject): Either[String, Option[Int]] =
      Right(json(name).flatMap(_.asNumber).flatMap(_.toInt))

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("integer"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "minimum" -> minimum.map(Json.fromInt),
      "maximum" -> maximum.map(Json.fromInt),
      "default" -> default.map(Json.fromInt)
    )
  }

  object integer {
    def required(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        minimum: Option[Int] = None,
        maximum: Option[Int] = None,
        default: Option[Int] = None
    ): RequiredInteger = RequiredInteger(name, title, description, minimum, maximum, default)

    def optional(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        minimum: Option[Int] = None,
        maximum: Option[Int] = None,
        default: Option[Int] = None
    ): OptionalInteger = OptionalInteger(name, title, description, minimum, maximum, default)
  }

  // ============================================================================
  // Boolean fields
  // ============================================================================

  case class RequiredBoolean(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      default: Option[Boolean] = None
  ) extends FormField[Boolean] {
    def isRequired: Boolean = true

    def extract(json: JsonObject): Either[String, Boolean] =
      json(name).flatMap(_.asBoolean).toRight(s"Missing or invalid field: $name")

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("boolean"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "default" -> default.map(Json.fromBoolean)
    )
  }

  case class OptionalBoolean(
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      default: Option[Boolean] = None
  ) extends FormField[Option[Boolean]] {
    def isRequired: Boolean = false

    def extract(json: JsonObject): Either[String, Option[Boolean]] =
      Right(json(name).flatMap(_.asBoolean))

    def toJsonSchema: JsonObject = build("type" -> Json.fromString("boolean"))(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "default" -> default.map(Json.fromBoolean)
    )
  }

  object boolean {
    def required(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        default: Option[Boolean] = None
    ): RequiredBoolean = RequiredBoolean(name, title, description, default)

    def optional(
        name: String,
        title: Option[String] = None,
        description: Option[String] = None,
        default: Option[Boolean] = None
    ): OptionalBoolean = OptionalBoolean(name, title, description, default)
  }

  // ============================================================================
  // Enum fields
  // ============================================================================

  case class RequiredEnum(
      name: String,
      values: List[String],
      title: Option[String] = None,
      description: Option[String] = None,
      default: Option[String] = None
  ) extends FormField[String] {
    def isRequired: Boolean = true

    def extract(json: JsonObject): Either[String, String] =
      json(name).flatMap(_.asString).filter(values.contains).toRight(s"Missing or invalid field: $name")

    def toJsonSchema: JsonObject = build(
      "type" -> Json.fromString("string"),
      "enum" -> Json.arr(values.map(Json.fromString)*)
    )(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "default" -> default.map(Json.fromString)
    )
  }

  case class OptionalEnum(
      name: String,
      values: List[String],
      title: Option[String] = None,
      description: Option[String] = None,
      default: Option[String] = None
  ) extends FormField[Option[String]] {
    def isRequired: Boolean = false

    def extract(json: JsonObject): Either[String, Option[String]] =
      Right(json(name).flatMap(_.asString).filter(values.contains))

    def toJsonSchema: JsonObject = build(
      "type" -> Json.fromString("string"),
      "enum" -> Json.arr(values.map(Json.fromString)*)
    )(
      "title" -> title.map(Json.fromString),
      "description" -> description.map(Json.fromString),
      "default" -> default.map(Json.fromString)
    )
  }

  object oneOf {
    def required(
        name: String,
        values: List[String],
        title: Option[String] = None,
        description: Option[String] = None,
        default: Option[String] = None
    ): RequiredEnum = RequiredEnum(name, values, title, description, default)

    def optional(
        name: String,
        values: List[String],
        title: Option[String] = None,
        description: Option[String] = None,
        default: Option[String] = None
    ): OptionalEnum = OptionalEnum(name, values, title, description, default)
  }
}

object FormFields {

  type ExtractTypes[T <: Tuple] <: Tuple = T match {
    case EmptyTuple           => EmptyTuple
    case FormField[a] *: tail => a *: ExtractTypes[tail]
  }

  def toJsonObject(fields: Tuple): JsonObject = {
    val fieldList = fields.productIterator.toList.asInstanceOf[List[FormField[?]]]
    val properties = fieldList.map(f => f.name -> Json.fromJsonObject(f.toJsonSchema))
    val requiredFields = fieldList.filter(_.isRequired).map(_.name)

    JsonObject(
      "type" -> Json.fromString("object"),
      "properties" -> Json.fromJsonObject(JsonObject.fromIterable(properties)),
      "required" -> Json.arr(requiredFields.map(Json.fromString)*)
    )
  }

  def extractAll[T <: Tuple](fields: T, json: JsonObject): Either[String, ExtractTypes[T]] = {
    val fieldList = fields.productIterator.toList.asInstanceOf[List[FormField[?]]]
    fieldList
      .foldRight[Either[String, Tuple]](Right(EmptyTuple)) { (field, acc) =>
        for {
          tail <- acc
          value <- field.extract(json)
        } yield value *: tail
      }
      .asInstanceOf[Either[String, ExtractTypes[T]]]
  }
}
