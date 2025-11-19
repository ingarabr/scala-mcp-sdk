package mcp.schema

import io.circe.Codec
import mcp.protocol.JsonSchemaType.*
import mcp.schema.McpSchemaTest.{Nested, Simple, SimpleMinimal, Team, WithClassDescription}
import munit.*

class McpSchemaTest extends FunSuite {

  test("derive simple minimal") {
    assertEquals(
      summon[McpSchema[SimpleMinimal]].jsonSchema,
      ObjectSchema(
        properties = Some(
          Map("a" -> IntegerSchema())
        ),
        required = Some(List("a")),
        description = None
      )
    )
  }

  test("derive simple") {
    assertEquals(
      summon[McpSchema[Simple]].jsonSchema,
      ObjectSchema(
        properties = Some(
          Map(
            "a" -> IntegerSchema(Some("The integer value for a")),
            "b" -> StringSchema()
          )
        ),
        required = Some(List("b")),
        description = None
      )
    )
  }

  test("derive nested") {
    assertEquals(
      summon[McpSchema[Nested]].jsonSchema,
      ObjectSchema(
        properties = Some(
          Map(
            "a" -> IntegerSchema(Some("The integer value for a")),
            "simple" -> ObjectSchema(
              properties = Some(
                Map(
                  "a" -> IntegerSchema(Some("The integer value for a")),
                  "b" -> StringSchema()
                )
              ),
              required = Some(List("b")),
              description = Some("A nested structure")
            )
          )
        ),
        required = Some(List("a", "simple")),
        description = None
      )
    )
  }

  test("class-level description") {
    assertEquals(
      summon[McpSchema[WithClassDescription]].jsonSchema,
      ObjectSchema(
        properties = Some(
          Map("name" -> StringSchema())
        ),
        required = Some(List("name")),
        description = Some("A person's information")
      )
    )
  }

  test("field overrides class description when nested") {
    assertEquals(
      summon[McpSchema[Team]].jsonSchema,
      ObjectSchema(
        properties = Some(
          Map(
            "name" -> StringSchema(),
            "leader" -> ObjectSchema(
              properties = Some(Map("name" -> StringSchema())),
              required = Some(List("name")),
              description = Some("Team leader") // Field annotation wins
            ),
            "deputy" -> ObjectSchema(
              properties = Some(Map("name" -> StringSchema())),
              required = Some(List("name")),
              description = Some("A person's information") // Class annotation used
            )
          )
        ),
        required = Some(List("name", "leader", "deputy")),
        description = Some("A team with members")
      )
    )
  }

}

object McpSchemaTest {
  case class SimpleMinimal(a: Int) derives Codec.AsObject
  case object SimpleMinimal {
    given McpSchema[SimpleMinimal] = McpSchema.derived
  }

  case class Simple(
      @description("The integer value for a")
      a: Option[Int],
      b: String
  ) derives Codec.AsObject

  case object Simple {
    given McpSchema[Simple] = McpSchema.derived
  }

  case class Nested(
      @description("The integer value for a")
      a: Int,
      @description("A nested structure")
      simple: Simple
  ) derives Codec.AsObject

  case object Nested {
    given McpSchema[Nested] = McpSchema.derived
  }

  @description("A person's information")
  case class WithClassDescription(name: String) derives Codec.AsObject

  case object WithClassDescription {
    given McpSchema[WithClassDescription] = McpSchema.derived
  }

  @description("A team with members")
  case class Team(
      name: String,
      @description("Team leader")
      leader: WithClassDescription,
      deputy: WithClassDescription
  ) derives Codec.AsObject

  case object Team {
    given McpSchema[Team] = McpSchema.derived
  }

}
