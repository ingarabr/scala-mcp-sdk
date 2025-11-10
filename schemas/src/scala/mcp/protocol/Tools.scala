package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** Definition of a tool the server can call.
  */
case class Tool(
    /** The name of the tool. */
    name: String,
    /** A human-readable description of the tool. */
    description: Option[String] = None,
    /** A JSON Schema object defining the expected parameters for the tool.
      */
    inputSchema: JsonObject,
    /** An optional JSON Schema object defining the structure of the tool's output returned in the structuredContent field of a
      * CallToolResult.
      */
    outputSchema: Option[JsonObject] = None,
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
