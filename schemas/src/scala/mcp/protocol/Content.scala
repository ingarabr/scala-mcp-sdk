package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** Content types that can be sent to or from an LLM.
  *
  * This is a discriminated union based on the `type` field.
  */
enum Content derives Codec.AsObject {

  /** Text provided to or from an LLM.
    */
  case Text(
      text: String,
      annotations: Option[Annotations] = None
  )

  /** An image provided to or from an LLM.
    */
  case Image(
      /** The base64-encoded image data. */
      data: String,

      /** The MIME type of the image. Different providers may support different image types. */
      mimeType: String,
      annotations: Option[Annotations] = None
  )

  /** Audio provided to or from an LLM.
    */
  case Audio(
      /** The base64-encoded audio data. */
      data: String,

      /** The MIME type of the audio. Different providers may support different audio types. */
      mimeType: String,
      annotations: Option[Annotations] = None
  )

  /** A resource embedded in a prompt or message.
    */
  case Resource(
      resource: ResourceContents,
      annotations: Option[Annotations] = None
  )
}

/** Base trait for resource contents.
  */
enum ResourceContents derives Codec.AsObject {

  /** The contents of a text resource.
    */
  case Text(
      /** The URI of this resource. */
      uri: String,

      /** The text content of the resource. */
      text: String,

      /** The MIME type of this resource, if known. */
      mimeType: Option[String] = None
  )

  /** The contents of a binary resource.
    */
  case Blob(
      /** The URI of this resource. */
      uri: String,

      /** A base64-encoded string representing the binary data of the item. */
      blob: String,

      /** The MIME type of this resource, if known. */
      mimeType: Option[String] = None
  )
}
