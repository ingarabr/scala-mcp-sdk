package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** A known resource that the server is capable of reading.
  */
case class Resource(
    /** The URI of this resource. */
    uri: String,
    /** A human-readable name for this resource. */
    name: String,
    /** A description of what this resource represents. */
    description: Option[String] = None,
    /** The MIME type of this resource, if known. */
    mimeType: Option[String] = None,
    /** Optional annotations for the client. */
    annotations: Option[Annotations] = None,
    /** The size of the resource in bytes, if known. */
    size: Option[Long] = None
) derives Codec.AsObject

/** A template description for resources available on the server.
  */
case class ResourceTemplate(
    /** A URI template (according to RFC 6570) that can be used to construct resource URIs. */
    uriTemplate: String,
    /** A human-readable name for the type of resource this template refers to. */
    name: String,
    /** A description of what this template is for. */
    description: Option[String] = None,
    /** The MIME type for all resources that match this template. This should only be included if all resources matching this template have
      * the same type.
      */
    mimeType: Option[String] = None,
    /** Optional annotations for the client. */
    annotations: Option[Annotations] = None
) derives Codec.AsObject

/** A reference to a resource or resource template definition.
  */
enum ResourceReference derives Codec.AsObject {

  /** Reference to a resource by URI.
    */
  case Uri(uri: String)

  /** Reference to a resource template by URI template.
    */
  case Template(uriTemplate: String)
}
