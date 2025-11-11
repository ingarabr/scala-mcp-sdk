package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** A JSON Schema type definition.
  *
  * This is a discriminated union representing different JSON Schema types. The top-level schema for MCP tools must always be an
  * ObjectSchema, but properties can be any schema type.
  *
  * The `type` field is automatically added as a discriminator during JSON encoding/decoding.
  */
enum JsonSchemaType {

  /** Object schema with properties and required fields.
    *
    * JSON type: "object"
    */
  case ObjectSchema(
      /** Property definitions where keys are property names and values are their schemas. */
      properties: Option[Map[String, JsonSchemaType]] = None,
      /** List of required property names. */
      required: Option[List[String]] = None,
      /** Optional description. */
      description: Option[String] = None
  )

  /** String schema.
    *
    * JSON type: "string"
    */
  case StringSchema(
      /** Optional description. */
      description: Option[String] = None
  )

  /** Number schema (floating point).
    *
    * JSON type: "number"
    */
  case NumberSchema(
      /** Optional description. */
      description: Option[String] = None
  )

  /** Integer schema.
    *
    * JSON type: "integer"
    */
  case IntegerSchema(
      /** Optional description. */
      description: Option[String] = None
  )

  /** Boolean schema.
    *
    * JSON type: "boolean"
    */
  case BooleanSchema(
      /** Optional description. */
      description: Option[String] = None
  )

  /** Null schema.
    *
    * JSON type: "null"
    */
  case NullSchema(
      /** Optional description. */
      description: Option[String] = None
  )

  /** Array schema.
    *
    * JSON type: "array"
    */
  case ArraySchema(
      /** Schema for array items. */
      items: Option[JsonSchemaType] = None,
      /** Optional description. */
      description: Option[String] = None
  )
}

object JsonSchemaType {
  import io.circe.derivation.Configuration

  private given Configuration = Configuration.default
    .withTransformConstructorNames {
      case "ObjectSchema"  => "object"
      case "StringSchema"  => "string"
      case "NumberSchema"  => "number"
      case "IntegerSchema" => "integer"
      case "BooleanSchema" => "boolean"
      case "NullSchema"    => "null"
      case "ArraySchema"   => "array"
      case other           => other
    }
    .withSnakeCaseMemberNames
    .withDiscriminator("type")

  given Codec.AsObject[JsonSchemaType] = Codec.AsObject.derivedConfigured
}

/** Definition of a tool the server can call.
  *
  * Note: Per MCP specification, `inputSchema` and `outputSchema` should always be `ObjectSchema` at the top level, but we use the general
  * `JsonSchemaType` for flexibility.
  */
case class Tool(
    /** The name of the tool. */
    name: String,
    /** A human-readable description of the tool. */
    description: Option[String] = None,
    /** A JSON Schema object defining the expected parameters for the tool. Should be an ObjectSchema in practice.
      */
    inputSchema: JsonSchemaType,
    /** An optional JSON Schema object defining the structure of the tool's output returned in the structuredContent field of a
      * CallToolResult. Should be an ObjectSchema in practice.
      */
    outputSchema: Option[JsonSchemaType] = None,
    /** Optional annotations for tool behavior hints. */
    annotations: Option[ToolAnnotations] = None,
    /** See General fields: _meta for notes on _meta usage. */
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Optional annotations for tools to provide hints about their behavior.
  */
case class ToolAnnotations(
    /** A human-readable title for the tool. */
    title: Option[String] = None,
    /** Hint that this tool is read-only and does not modify state. Default: false
      */
    readOnlyHint: Option[Boolean] = None,
    /** Hint that this tool performs destructive operations. Only valid if readOnlyHint is false. Default: true
      */
    destructiveHint: Option[Boolean] = None,
    /** Hint that repeated calls with the same parameters will have the same effect. Only valid if readOnlyHint is false. Default: false
      */
    idempotentHint: Option[Boolean] = None,
    /** Hint that this tool operates in an open world (may return different results over time). Default: true
      */
    openWorldHint: Option[Boolean] = None
) derives Codec.AsObject
