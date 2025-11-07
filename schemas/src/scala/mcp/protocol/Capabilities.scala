package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** Capabilities a client may support. Known capabilities are defined here, in this schema, but this is not a closed set: any client can
  * define its own, additional capabilities.
  */
case class ClientCapabilities(
    /** Experimental, non-standard capabilities that the client supports. */
    experimental: Option[JsonObject] = None,
    /** Present if the client supports listing roots. */
    roots: Option[RootsCapability] = None,
    /** Present if the client supports sampling from an LLM. */
    sampling: Option[JsonObject] = None
) derives Codec.AsObject

/** Capability for roots listing support.
  */
case class RootsCapability(
    /** Whether the client supports notifications for changes to the roots list. */
    listChanged: Option[Boolean] = None
) derives Codec.AsObject

/** Capabilities that a server may support. Known capabilities are defined here, in this schema, but this is not a closed set: any server
  * can define its own, additional capabilities.
  */
case class ServerCapabilities(
    /** Experimental, non-standard capabilities that the server supports. */
    experimental: Option[JsonObject] = None,
    /** Present if the server supports sending log messages to the client. */
    logging: Option[JsonObject] = None,
    /** Present if the server supports argument autocompletion suggestions. */
    completions: Option[JsonObject] = None,
    /** Present if the server offers any prompt templates. */
    prompts: Option[PromptsCapability] = None,
    /** Present if the server offers any resources to read. */
    resources: Option[ResourcesCapability] = None,
    /** Present if the server offers any tools to call. */
    tools: Option[ToolsCapability] = None
) derives Codec.AsObject

/** Capability for prompts support.
  */
case class PromptsCapability(
    /** Whether this server supports notifications for changes to the prompt list. */
    listChanged: Option[Boolean] = None
) derives Codec.AsObject

/** Capability for resources support.
  */
case class ResourcesCapability(
    /** Whether this server supports subscribing to resource updates. */
    subscribe: Option[Boolean] = None,
    /** Whether this server supports notifications for changes to the resource list. */
    listChanged: Option[Boolean] = None
) derives Codec.AsObject

/** Capability for tools support.
  */
case class ToolsCapability(
    /** Whether this server supports notifications for changes to the tool list. */
    listChanged: Option[Boolean] = None
) derives Codec.AsObject
