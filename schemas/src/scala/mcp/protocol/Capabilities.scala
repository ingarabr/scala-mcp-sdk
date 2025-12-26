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
    sampling: Option[JsonObject] = None,
    /** Present if the client supports elicitation from the server. */
    elicitation: Option[JsonObject] = None,
    /** Present if the client supports task-augmented requests. */
    tasks: Option[ClientTasksCapability] = None
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
    tools: Option[ToolsCapability] = None,
    /** Present if the server supports async tasks. */
    tasks: Option[TasksCapability] = None
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

/** Server capability for task-augmented requests.
  */
case class TasksCapability(
    /** Whether this server supports tasks/list. */
    list: Option[JsonObject] = None,
    /** Whether this server supports tasks/cancel. */
    cancel: Option[JsonObject] = None,
    /** Specifies which request types can be augmented with tasks. */
    requests: Option[ServerTasksRequests] = None
) derives Codec.AsObject

/** Task support for specific request types (server-side).
  */
case class ServerTasksRequests(
    /** Task support for tool-related requests. */
    tools: Option[ServerTasksTools] = None
) derives Codec.AsObject

/** Task support for tool requests (server-side).
  */
case class ServerTasksTools(
    /** Whether the server supports task-augmented tools/call requests. */
    call: Option[JsonObject] = None
) derives Codec.AsObject

/** Client capability for task-augmented requests.
  */
case class ClientTasksCapability(
    /** Whether this client supports tasks/list. */
    list: Option[JsonObject] = None,
    /** Whether this client supports tasks/cancel. */
    cancel: Option[JsonObject] = None,
    /** Specifies which request types can be augmented with tasks. */
    requests: Option[ClientTasksRequests] = None
) derives Codec.AsObject

/** Task support for specific request types (client-side).
  */
case class ClientTasksRequests(
    /** Task support for sampling-related requests. */
    sampling: Option[ClientTasksSampling] = None,
    /** Task support for elicitation-related requests. */
    elicitation: Option[ClientTasksElicitation] = None
) derives Codec.AsObject

/** Task support for sampling requests (client-side).
  */
case class ClientTasksSampling(
    /** Whether the client supports task-augmented sampling/createMessage requests. */
    createMessage: Option[JsonObject] = None
) derives Codec.AsObject

/** Task support for elicitation requests (client-side).
  */
case class ClientTasksElicitation(
    /** Whether the client supports task-augmented elicitation/create requests. */
    create: Option[JsonObject] = None
) derives Codec.AsObject
