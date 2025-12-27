package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.derivation.Configuration

/** Content types that can be sent to or from an LLM.
  *
  * This is a discriminated union based on the `type` field.
  */
enum Content {

  /** Text provided to or from an LLM.
    */
  case Text(
      text: String,
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** An image provided to or from an LLM.
    */
  case Image(
      /** The base64-encoded image data. */
      data: String,

      /** The MIME type of the image. Different providers may support different image types. */
      mimeType: String,
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** Audio provided to or from an LLM.
    */
  case Audio(
      /** The base64-encoded audio data. */
      data: String,

      /** The MIME type of the audio. Different providers may support different audio types. */
      mimeType: String,
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** An embedded resource (the contents of a resource).
    *
    * JSON type: "resource"
    */
  case Resource(
      resource: ResourceContents,
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** A reference/link to a resource that the server is capable of reading.
    *
    * JSON type: "resource_link"
    *
    * Note: resource links returned by tools are not guaranteed to appear in the results of resources/list requests.
    */
  case ResourceLink(
      /** The URI of this resource. */
      uri: String,

      /** The name of the resource. */
      name: String,

      /** A description of what this resource represents. */
      description: Option[String] = None,

      /** The MIME type of this resource, if known. */
      mimeType: Option[String] = None,

      /** Optional annotations for the client. */
      annotations: Option[Annotations] = None,

      /** The size of the raw resource content, in bytes, if known. */
      size: Option[Long] = None,

      /** An optional title for display purposes. */
      title: Option[String] = None,

      /** See General fields: _meta for notes on _meta usage. */
      _meta: Option[JsonObject] = None
  )

  /** A request from the assistant to call a tool.
    *
    * JSON type: "tool_use"
    *
    * Used in sampling responses when the model wants to use a tool.
    */
  case ToolUse(
      /** A unique identifier for this tool use. Used to match tool results to their corresponding tool uses. */
      id: String,

      /** The name of the tool to call. */
      name: String,

      /** The arguments to pass to the tool, conforming to the tool's input schema. */
      input: JsonObject,

      /** Optional metadata about the tool use. Clients SHOULD preserve this field when including tool uses in subsequent sampling requests
        * to enable caching optimizations.
        */
      _meta: Option[JsonObject] = None
  )

  /** The result of a tool use, provided by the user back to the assistant.
    *
    * JSON type: "tool_result"
    *
    * Used in sampling requests to provide the result of a tool call.
    */
  case ToolResult(
      /** The ID of the tool use this result corresponds to. This MUST match the ID from a previous ToolUse. */
      toolUseId: String,

      /** The unstructured result content of the tool use. Can include text, images, audio, resource links, and embedded resources. */
      content: List[Content],

      /** An optional structured result object. If the tool defined an outputSchema, this SHOULD conform to that schema. */
      structuredContent: Option[JsonObject] = None,

      /** Whether the tool use resulted in an error. If true, the content typically describes the error that occurred. */
      isError: Option[Boolean] = None,

      /** Optional metadata about the tool result. Clients SHOULD preserve this field when including tool results in subsequent sampling
        * requests to enable caching optimizations.
        */
      _meta: Option[JsonObject] = None
  )
}

object Content {
  private given Configuration = Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames.withDiscriminator("type")
  given Codec.AsObject[Content] = Codec.AsObject.derivedConfigured
}

/** Base trait for resource contents.
  */
enum ResourceContents {

  /** The contents of a text resource.
    */
  case Text(
      /** The URI of this resource. */
      uri: String,

      /** The text content of the resource. */
      text: String,

      /** The MIME type of this resource, if known. */
      mimeType: Option[String] = None,

      /** See General fields: _meta for notes on _meta usage. */
      _meta: Option[JsonObject] = None
  )

  /** The contents of a binary resource.
    */
  case Blob(
      /** The URI of this resource. */
      uri: String,

      /** A base64-encoded string representing the binary data of the item. */
      blob: String,

      /** The MIME type of this resource, if known. */
      mimeType: Option[String] = None,

      /** See General fields: _meta for notes on _meta usage. */
      _meta: Option[JsonObject] = None
  )
}

object ResourceContents {
  private given Configuration = Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames.withDiscriminator("type")
  given Codec.AsObject[ResourceContents] = Codec.AsObject.derivedConfigured
}
