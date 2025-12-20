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
    size: Option[Long] = None,
    /** An optional title for display purposes. If not provided, the name should be used for display. */
    title: Option[String] = None,
    /** Optional set of sized icons that the client can display in a user interface. */
    icons: Option[List[Icon]] = None,
    /** See General fields: _meta for notes on _meta usage. */
    _meta: Option[JsonObject] = None
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
    annotations: Option[Annotations] = None,
    /** An optional title for display purposes. If not provided, the name should be used for display. */
    title: Option[String] = None,
    /** Optional set of sized icons that the client can display in a user interface. */
    icons: Option[List[Icon]] = None,
    /** See General fields: _meta for notes on _meta usage. */
    _meta: Option[JsonObject] = None
) derives Codec.AsObject
