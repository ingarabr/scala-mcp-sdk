package mcp.server

import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import mcp.protocol.JsonSchemaType
import mcp.protocol.JsonSchemaType.*
import munit.FunSuite

class InputDefSuite extends FunSuite {

  // ===========================================================================
  // InputDef construction: Named tuple (flat)
  // ===========================================================================

  test("named tuple: flat, all required") {
    type T = (name: String, age: Int)
    val defn = InputDef[T](
      name = InputField[String]("User name"),
      age = InputField[Int]("User age")
    )

    assertEquals(
      defn.jsonSchema,
      ObjectSchema(
        properties = Some(
          Map(
            "name" -> StringSchema(description = Some("User name")),
            "age" -> IntegerSchema(description = Some("User age"))
          )
        ),
        required = Some(List("name", "age"))
      )
    )
  }

  test("named tuple: with optional field") {
    type T = (name: String, nickname: Option[String])
    val defn = InputDef[T](
      name = InputField[String]("Name"),
      nickname = InputField[Option[String]]("Optional nickname")
    )

    assertEquals(defn.jsonSchema.required, Some(List("name")))
    assert(defn.jsonSchema.properties.get.contains("nickname"))
  }

  test("named tuple: extraction happy path") {
    type T = (a: String, b: Int)
    val defn = InputDef[T](
      a = InputField[String]("Field A"),
      b = InputField[Int]("Field B")
    )

    val json = JsonObject("a" -> Json.fromString("hello"), "b" -> Json.fromInt(42))
    val result = defn.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
    val value = result.toOption.get
    assertEquals(value.asInstanceOf[(String, Int)]._1, "hello")
    assertEquals(value.asInstanceOf[(String, Int)]._2, 42)
  }

  test("named tuple: extraction with optional present") {
    type T = (name: String, age: Option[Int])
    val defn = InputDef[T](
      name = InputField[String]("Name"),
      age = InputField[Option[Int]]("Age")
    )

    val json = JsonObject("name" -> Json.fromString("Alice"), "age" -> Json.fromInt(30))
    val result = defn.extract(json)
    assert(result.isRight)
  }

  test("named tuple: extraction with optional absent") {
    type T = (name: String, age: Option[Int])
    val defn = InputDef[T](
      name = InputField[String]("Name"),
      age = InputField[Option[Int]]("Age")
    )

    val json = JsonObject("name" -> Json.fromString("Bob"))
    val result = defn.extract(json)
    assert(result.isRight)
  }

  test("named tuple: extraction missing required field") {
    type T = (name: String, age: Int)
    val defn = InputDef[T](
      name = InputField[String]("Name"),
      age = InputField[Int]("Age")
    )

    val json = JsonObject("name" -> Json.fromString("Alice"))
    val result = defn.extract(json)
    assert(result.isLeft, "Should fail on missing required field")
  }

  // ===========================================================================
  // InputDef construction: Case class with withDecoder
  // ===========================================================================

  test("case class: withDecoder schema generation") {
    case class MyInput(query: String, limit: Option[Int]) derives Decoder
    val defn = InputDef.withDecoder[MyInput](
      query = InputField[String]("Search query"),
      limit = InputField[Option[Int]]("Max results")
    )

    assertEquals(defn.jsonSchema.required, Some(List("query")))
    assert(defn.jsonSchema.properties.get.contains("query"))
    assert(defn.jsonSchema.properties.get.contains("limit"))
  }

  test("case class: withDecoder extraction") {
    case class MyInput(query: String, limit: Option[Int]) derives Decoder
    val defn = InputDef.withDecoder[MyInput](
      query = InputField[String]("Search query"),
      limit = InputField[Option[Int]]("Max results")
    )

    val json = JsonObject("query" -> Json.fromString("test"), "limit" -> Json.fromInt(10))
    val result = defn.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }

  // ===========================================================================
  // InputDef construction: Dynamic
  // ===========================================================================

  test("dynamic: schema from field list") {
    val defn = InputDef.dynamic(
      List(
        "name" -> InputField[String]("Name"),
        "active" -> InputField[Boolean]("Is active"),
        "score" -> InputField[Option[Double]]("Score")
      )
    )

    assertEquals(defn.jsonSchema.required, Some(List("name", "active")))
    assertEquals(defn.jsonSchema.properties.get.size, 3)
  }

  test("dynamic: extraction returns raw JsonObject") {
    val defn = InputDef.dynamic(
      List(
        "x" -> InputField[String]("X")
      )
    )

    val json = JsonObject("x" -> Json.fromString("hello"), "extra" -> Json.fromInt(1))
    val result = defn.extract(json)
    assert(result.isRight)
    assertEquals(result.toOption.get, json)
  }

  // ===========================================================================
  // InputDef construction: Raw
  // ===========================================================================

  test("raw: passthrough schema") {
    val schema: ObjectSchema = ObjectSchema(
      properties = Some(Map("q" -> StringSchema(description = Some("query")))),
      required = Some(List("q"))
    )
    val defn = InputDef.raw[JsonObject](schema, Decoder[JsonObject])

    assertEquals(defn.jsonSchema, schema)

    val json = JsonObject("q" -> Json.fromString("test"))
    val result = defn.extract(json)
    assert(result.isRight)
  }

  // ===========================================================================
  // Nested object
  // ===========================================================================

  test("nested object: schema generation") {
    type Inner = (key: String, value: String)
    val innerDef = InputDef[Inner](
      key = InputField[String]("Key"),
      value = InputField[String]("Value")
    )

    type Outer = (name: String, config: Inner)
    val outerDef = InputDef[Outer](
      name = InputField[String]("Name"),
      config = InputField.obj("Configuration", innerDef)
    )

    val props = outerDef.jsonSchema.properties.get
    assert(props.contains("config"))
    props("config") match {
      case obj: ObjectSchema =>
        assert(obj.properties.get.contains("key"))
        assert(obj.properties.get.contains("value"))
        assertEquals(obj.description, Some("Configuration"))
      case other => fail(s"Expected ObjectSchema, got $other")
    }
  }

  test("nested object: extraction") {
    type Inner = (x: Int, y: Int)
    val innerDef = InputDef[Inner](
      x = InputField[Int]("X coord"),
      y = InputField[Int]("Y coord")
    )

    type Outer = (label: String, point: Inner)
    val outerDef = InputDef[Outer](
      label = InputField[String]("Label"),
      point = InputField.obj("Point", innerDef)
    )

    val json = JsonObject(
      "label" -> Json.fromString("origin"),
      "point" -> Json.obj("x" -> Json.fromInt(0), "y" -> Json.fromInt(0))
    )
    val result = outerDef.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }

  test("optional nested object: absent") {
    type Inner = (key: String, val2: Int)
    val innerDef = InputDef[Inner](
      key = InputField[String]("Key"),
      val2 = InputField[Int]("Val2")
    )

    type Outer = (name: String, extra: Option[Inner])
    val outerDef = InputDef[Outer](
      name = InputField[String]("Name"),
      extra = InputField.optionalObject("Extra", innerDef)
    )

    val json = JsonObject("name" -> Json.fromString("test"))
    val result = outerDef.extract(json)
    assert(result.isRight)
  }

  // ===========================================================================
  // Deeply nested (3 levels)
  // ===========================================================================

  test("deeply nested: 3 levels") {
    type L3 = (value: String, extra: Option[Int])
    val l3Def = InputDef[L3](
      value = InputField[String]("Value"),
      extra = InputField[Option[Int]]("Extra")
    )

    type L2 = (nested: L3, label: String)
    val l2Def = InputDef[L2](
      nested = InputField.obj("Level 3", l3Def),
      label = InputField[String]("Label")
    )

    type L1 = (top: L2, id: Int)
    val l1Def = InputDef[L1](
      top = InputField.obj("Level 2", l2Def),
      id = InputField[Int]("ID")
    )

    // Verify schema nesting
    val l1Props = l1Def.jsonSchema.properties.get
    l1Props("top") match {
      case obj: ObjectSchema =>
        obj.properties.get("nested") match {
          case inner: ObjectSchema =>
            assert(inner.properties.get.contains("value"))
          case other => fail(s"Expected ObjectSchema at L3, got $other")
        }
      case other => fail(s"Expected ObjectSchema at L2, got $other")
    }

    // Verify extraction
    val json = JsonObject(
      "id" -> Json.fromInt(1),
      "top" -> Json.obj(
        "label" -> Json.fromString("lvl2"),
        "nested" -> Json.obj(
          "value" -> Json.fromString("deep")
        )
      )
    )
    val result = l1Def.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }

  // ===========================================================================
  // Array of primitives
  // ===========================================================================

  test("array of strings: schema") {
    type T = (label: String, tags: List[String])
    val defn = InputDef[T](
      label = InputField[String]("Label"),
      tags = InputField.array[String]("Tags")
    )

    defn.jsonSchema.properties.get("tags") match {
      case arr: ArraySchema =>
        assertEquals(arr.description, Some("Tags"))
        arr.items match {
          case Some(_: StringSchema) => () // ok
          case other                 => fail(s"Expected StringSchema items, got $other")
        }
      case other => fail(s"Expected ArraySchema, got $other")
    }
  }

  test("array of strings: extraction") {
    type T = (label: String, tags: List[String])
    val defn = InputDef[T](
      label = InputField[String]("Label"),
      tags = InputField.array[String]("Tags")
    )

    val json = JsonObject("label" -> Json.fromString("test"), "tags" -> Json.arr(Json.fromString("a"), Json.fromString("b")))
    val result = defn.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }

  test("optional array: absent") {
    type T = (label: String, tags: Option[List[String]])
    val defn = InputDef[T](
      label = InputField[String]("Label"),
      tags = InputField.optionalArray[String]("Tags")
    )

    val json = JsonObject("label" -> Json.fromString("test"))
    val result = defn.extract(json)
    assert(result.isRight)
  }

  // ===========================================================================
  // Array of objects
  // ===========================================================================

  test("array of objects: schema") {
    type Item = (name: String, value: Int)
    val itemDef = InputDef[Item](
      name = InputField[String]("Item name"),
      value = InputField[Int]("Item value")
    )

    type T = (label: String, items: List[Item])
    val defn = InputDef[T](
      label = InputField[String]("Label"),
      items = InputField.array("Items", itemDef)
    )

    defn.jsonSchema.properties.get("items") match {
      case arr: ArraySchema =>
        assertEquals(arr.description, Some("Items"))
        arr.items match {
          case Some(obj: ObjectSchema) =>
            assert(obj.properties.get.contains("name"))
            assert(obj.properties.get.contains("value"))
          case other => fail(s"Expected ObjectSchema items, got $other")
        }
      case other => fail(s"Expected ArraySchema, got $other")
    }
  }

  test("array of objects: extraction") {
    type Item = (name: String, value: Int)
    val itemDef = InputDef[Item](
      name = InputField[String]("Name"),
      value = InputField[Int]("Value")
    )

    type T = (label: String, items: List[Item])
    val defn = InputDef[T](
      label = InputField[String]("Label"),
      items = InputField.array("Items", itemDef)
    )

    val json = JsonObject(
      "label" -> Json.fromString("test"),
      "items" -> Json.arr(
        Json.obj("name" -> Json.fromString("a"), "value" -> Json.fromInt(1)),
        Json.obj("name" -> Json.fromString("b"), "value" -> Json.fromInt(2))
      )
    )
    val result = defn.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }

  test("array of objects with optional fields: extraction") {
    type Item = (name: String, score: Option[Double])
    val itemDef = InputDef[Item](
      name = InputField[String]("Name"),
      score = InputField[Option[Double]]("Score")
    )

    type T = (label: String, items: List[Item])
    val defn = InputDef[T](
      label = InputField[String]("Label"),
      items = InputField.array("Items", itemDef)
    )

    val json = JsonObject(
      "label" -> Json.fromString("test"),
      "items" -> Json.arr(
        Json.obj("name" -> Json.fromString("a"), "score" -> Json.fromDoubleOrNull(0.9)),
        Json.obj("name" -> Json.fromString("b")) // score omitted
      )
    )
    val result = defn.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }

  // ===========================================================================
  // Schema toJsonObject conversion
  // ===========================================================================

  test("toJsonObject: produces valid JSON for elicitation") {
    import mcp.protocol.JsonSchemaType.toJsonObject

    val schema = ObjectSchema(
      properties = Some(
        Map(
          "name" -> StringSchema(description = Some("Name")),
          "age" -> IntegerSchema(description = Some("Age"))
        )
      ),
      required = Some(List("name", "age"))
    )

    val jsonObj = schema.toJsonObject
    assertEquals(jsonObj("type").flatMap(_.asString), Some("object"))
    assert(jsonObj("properties").flatMap(_.asObject).isDefined)
    assert(jsonObj("required").flatMap(_.asArray).isDefined)
  }

  test("toJsonObject: omits null fields") {
    import mcp.protocol.JsonSchemaType.toJsonObject

    val schema = StringSchema(description = Some("test"))
    val jsonObj = schema.toJsonObject

    assertEquals(jsonObj("type").flatMap(_.asString), Some("string"))
    assertEquals(jsonObj("description").flatMap(_.asString), Some("test"))
    // title, minLength, etc. should not be present
    assert(jsonObj("title").isEmpty || jsonObj("title").exists(_.isNull) == false)
  }

  // ===========================================================================
  // Decoder integration
  // ===========================================================================

  test("decoder: works with Circe's as[A]") {
    type T = (name: String, value: Int)
    val defn = InputDef[T](
      name = InputField[String]("Name"),
      value = InputField[Int]("Value")
    )

    val json = Json.obj("name" -> Json.fromString("test"), "value" -> Json.fromInt(42))
    val result = json.as(using defn.decoder)
    assert(result.isRight, s"Decoder failed: $result")
  }

  // ===========================================================================
  // InputField with title and default
  // ===========================================================================

  test("InputField: title and description in schema") {
    val field = InputField[String](title = Some("Display Name"), description = Some("The name"), default = Some("anon"))
    field.toJsonSchema match {
      case s: StringSchema =>
        assertEquals(s.title, Some("Display Name"))
        assertEquals(s.description, Some("The name"))
        assertEquals(s.default, Some("anon"))
      case other => fail(s"Expected StringSchema, got $other")
    }
  }

  // ===========================================================================
  // Complex example: DeployTool-style nested + arrays
  // ===========================================================================

  test("complex: nested objects with arrays") {
    type EnvVar = (key: String, value: String)
    val envVarDef = InputDef[EnvVar](
      key = InputField[String]("Variable name"),
      value = InputField[String]("Variable value")
    )

    type EnvConfig = (name: String, replicas: Int, envVars: Option[List[EnvVar]])
    val envConfigDef = InputDef[EnvConfig](
      name = InputField[String]("Environment name"),
      replicas = InputField[Int]("Number of replicas"),
      envVars = InputField.optionalArray("Environment variables", envVarDef)
    )

    type Input = (service: String, targets: List[EnvConfig], dryRun: Option[Boolean])
    val inputDef = InputDef[Input](
      service = InputField[String]("Service name"),
      targets = InputField.array("Target environments", envConfigDef),
      dryRun = InputField[Option[Boolean]]("Dry run mode")
    )

    // Verify schema structure
    assertEquals(inputDef.jsonSchema.required, Some(List("service", "targets")))

    val props = inputDef.jsonSchema.properties.get
    props("targets") match {
      case arr: ArraySchema =>
        arr.items match {
          case Some(obj: ObjectSchema) =>
            assertEquals(obj.required, Some(List("name", "replicas")))
            obj.properties.get("envVars") match {
              case innerArr: ArraySchema =>
                innerArr.items match {
                  case Some(envObj: ObjectSchema) =>
                    assert(envObj.properties.get.contains("key"))
                    assert(envObj.properties.get.contains("value"))
                  case other => fail(s"Expected ObjectSchema for envVar items, got $other")
                }
              case other => fail(s"Expected ArraySchema for envVars, got $other")
            }
          case other => fail(s"Expected ObjectSchema for target items, got $other")
        }
      case other => fail(s"Expected ArraySchema for targets, got $other")
    }

    // Verify extraction
    val json = JsonObject(
      "service" -> Json.fromString("api"),
      "targets" -> Json.arr(
        Json.obj(
          "name" -> Json.fromString("staging"),
          "replicas" -> Json.fromInt(2),
          "envVars" -> Json.arr(
            Json.obj("key" -> Json.fromString("PORT"), "value" -> Json.fromString("8080"))
          )
        ),
        Json.obj(
          "name" -> Json.fromString("prod"),
          "replicas" -> Json.fromInt(5)
          // envVars omitted (optional)
        )
      )
    )

    val result = inputDef.extract(json)
    assert(result.isRight, s"Extraction failed: $result")
  }
}
