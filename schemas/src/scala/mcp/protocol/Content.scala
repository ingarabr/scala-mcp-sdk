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
