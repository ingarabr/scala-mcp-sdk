package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.derivation.Configuration

// ============================================================================
// INITIALIZATION
// ============================================================================

/** This request is sent from the client to the server when it first connects, asking it to begin initialization.
  */
case class InitializeRequest(
    /** The latest version of the Model Context Protocol that the client supports. The client MAY decide to support older versions as well.
      */
    protocolVersion: String,
    capabilities: ClientCapabilities,
    clientInfo: Implementation
) derives Codec.AsObject

/** After receiving an initialize request from the client, the server sends this response.
  */
case class InitializeResult(
    /** The version of the Model Context Protocol that the server wants to use. This may not match the version that the client requested. If
      * the client cannot support this version, it MUST disconnect.
      */
    protocolVersion: String,
    capabilities: ServerCapabilities,
    serverInfo: Implementation,
    /** Instructions describing how to use the server and its features.
      *
      * This can be used by clients to improve the LLM's understanding of available tools, resources, etc. It can be thought of like a
      * "hint" to the model. For example, this information MAY be added to the system prompt.
      */
    instructions: Option[String] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

// ============================================================================
// PAGINATION
// ============================================================================

/** Pagination support for list operations.
  */
case class PaginatedRequest(
    /** An opaque token representing the current pagination position. If provided, the server should return results starting after this
      * cursor.
      */
    cursor: Option[Cursor] = None
) derives Codec.AsObject

// ============================================================================
// RESOURCES
// ============================================================================

/** Sent from the client to request a list of resources the server has.
  */
case class ListResourcesRequest(
    cursor: Option[Cursor] = None
) derives Codec.AsObject

/** The server's response to a resources/list request from the client.
  */
case class ListResourcesResult(
    resources: List[Resource],
    /** An opaque token representing the pagination position after the last returned result. */
    nextCursor: Option[Cursor] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Sent from the client to request a list of resource templates the server has.
  */
case class ListResourceTemplatesRequest(
    cursor: Option[Cursor] = None
) derives Codec.AsObject

/** The server's response to a resources/templates/list request.
  */
case class ListResourceTemplatesResult(
    resourceTemplates: List[ResourceTemplate],
    nextCursor: Option[Cursor] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Sent from the client to the server, to read a specific resource URI.
  */
case class ReadResourceRequest(
    /** The URI of the resource to read. */
    uri: String
) derives Codec.AsObject

/** The server's response to a resources/read request.
  */
case class ReadResourceResult(
    contents: List[ResourceContents],
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Sent from the client to request that the server subscribe to resource updates.
  */
case class SubscribeRequest(
    /** The URI of the resource to subscribe to. */
    uri: String
) derives Codec.AsObject

/** Sent from the client to request that the server unsubscribe from resource updates.
  */
case class UnsubscribeRequest(
    /** The URI of the resource to unsubscribe from. */
    uri: String
) derives Codec.AsObject

// ============================================================================
// PROMPTS
// ============================================================================

/** Sent from the client to request a list of prompts and prompt templates the server has.
  */
case class ListPromptsRequest(
    cursor: Option[Cursor] = None
) derives Codec.AsObject

/** The server's response to a prompts/list request.
  */
case class ListPromptsResult(
    prompts: List[Prompt],
    nextCursor: Option[Cursor] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Used by the client to get a prompt provided by the server.
  */
case class GetPromptRequest(
    /** The name of the prompt or prompt template. */
    name: String,
    /** Arguments to use for templating the prompt. */
    arguments: Option[JsonObject] = None
) derives Codec.AsObject

/** The server's response to a prompts/get request.
  */
case class GetPromptResult(
    /** An optional description for the prompt. */
    description: Option[String] = None,
    messages: List[PromptMessage],
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

// ============================================================================
// TOOLS
// ============================================================================

/** Sent from the client to request a list of tools the server has.
  */
case class ListToolsRequest(
    cursor: Option[Cursor] = None
) derives Codec.AsObject

/** The server's response to a tools/list request.
  */
case class ListToolsResult(
    tools: List[Tool],
    nextCursor: Option[Cursor] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Used by the client to invoke a tool provided by the server.
  */
case class CallToolRequest(
    /** The name of the tool to call. */
    name: String,
    /** Arguments to pass to the tool. */
    arguments: Option[JsonObject] = None
) derives Codec.AsObject

/** The server's response to a tools/call request.
  */
case class CallToolResult(
    content: List[Content],
    /** An optional JSON object that represents the structured result of the tool call. */
    structuredContent: Option[JsonObject] = None,
    /** Whether the tool execution resulted in an error. */
    isError: Option[Boolean] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

// ============================================================================
// LOGGING
// ============================================================================

/** A request from the client to the server, to enable or adjust logging.
  */
case class SetLevelRequest(
    /** The level of logging that the client wants to receive from the server. */
    level: LoggingLevel
) derives Codec.AsObject

/** Notification of a log message from the server to the client.
  */
case class LoggingMessageNotification(
    /** The severity of this log message. */
    level: LoggingLevel,
    /** The name of the logger that produced this message. */
    logger: Option[String] = None,
    /** The data to be logged. */
    data: Json
) derives Codec.AsObject

// ============================================================================
// SAMPLING
// ============================================================================

/** A message for sampling from an LLM.
  */
case class SamplingMessage(
    role: Role,
    content: Content
) derives Codec.AsObject

/** Model preferences for sampling operations.
  */
case class ModelPreferences(
    /** Optional hints to use for model selection. */
    hints: Option[List[ModelHint]] = None,
    /** How much to prioritize cost when selecting a model. Range: 0-1 */
    costPriority: Option[Double] = None,
    /** How much to prioritize sampling speed when selecting a model. Range: 0-1 */
    speedPriority: Option[Double] = None,
    /** How much to prioritize intelligence/capabilities when selecting a model. Range: 0-1 */
    intelligencePriority: Option[Double] = None
) derives Codec.AsObject

/** Hints for model selection.
  */
case class ModelHint(
    /** A hint for a model name. The client SHOULD treat this as a substring of a model name. */
    name: Option[String] = None
) derives Codec.AsObject

/** A request from the server to sample from an LLM via the client.
  */
case class CreateMessageRequest(
    messages: List[SamplingMessage],
    /** An optional system prompt the server wants to use for sampling. */
    systemPrompt: Option[String] = None,
    /** Context to include with the request. */
    includeContext: Option[String] = None, // "none" | "thisServer" | "allServers"
    /** The temperature to use for sampling. */
    temperature: Option[Double] = None,
    /** The maximum number of tokens to sample. */
    maxTokens: Int,
    /** Optional strings that will cause the model to stop sampling. */
    stopSequences: Option[List[String]] = None,
    /** Optional metadata for model selection. */
    metadata: Option[JsonObject] = None,
    /** Preferences for which model to use. */
    modelPreferences: Option[ModelPreferences] = None
) derives Codec.AsObject

/** The client's response to a sampling/createMessage request.
  */
case class CreateMessageResult(
    /** The role of the message sender. */
    role: Role,
    /** The content of the response. */
    content: Content,
    /** The name of the model that generated this response. */
    model: String,
    /** The reason why sampling stopped. */
    stopReason: Option[String] = None, // "endTurn" | "stopSequence" | "maxTokens" | custom
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

// ============================================================================
// ROOTS
// ============================================================================

/** Represents a root directory or file that the server can operate on.
  */
case class Root(
    /** The URI of the root. This MUST be a valid URI. */
    uri: String,
    /** An optional name for the root. */
    name: Option[String] = None,
    /** See General fields: _meta for notes on _meta usage. */
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Sent from the server to request a list of root URIs from the client.
  */
case class ListRootsRequest() derives Codec.AsObject

/** The client's response to a roots/list request.
  */
case class ListRootsResult(
    roots: List[Root],
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

// ============================================================================
// COMPLETIONS
// ============================================================================

/** A reference to a resource or prompt for completion.
  *
  * This is a discriminated union based on the `type` field.
  */
enum CompletionReference {

  /** Reference to a resource template.
    *
    * JSON type: "ref/resource"
    */
  case ResourceTemplate(
      /** The URI or URI template of the resource. */
      uri: String
  )

  /** Reference to a prompt.
    *
    * JSON type: "ref/prompt"
    */
  case Prompt(
      /** The name of the prompt. */
      name: String,
      /** An optional title for display purposes. */
      title: Option[String] = None
  )
}

object CompletionReference {
  private given Configuration = Configuration.default
    .withTransformConstructorNames {
      case "ResourceTemplate" => "ref/resource"
      case "Prompt"           => "ref/prompt"
      case other              => other // Should never happen, but satisfies exhaustiveness check
    }
    .withSnakeCaseMemberNames
    .withDiscriminator("type")
  given Codec.AsObject[CompletionReference] = Codec.AsObject.derivedConfigured
}

/** A request from the client to ask for completion suggestions.
  */
case class CompleteRequest(
    ref: CompletionReference,
    argument: CompletionArgument
) derives Codec.AsObject

/** Describes an argument being completed.
  */
case class CompletionArgument(
    /** The name of the argument. */
    name: String,
    /** The current value of the argument to be completed. */
    value: String
) derives Codec.AsObject

/** The server's response to a completion/complete request.
  */
case class CompleteResult(
    completion: CompletionCompletion,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

/** Completion values returned from the server.
  */
case class CompletionCompletion(
    /** The completion values. */
    values: List[String],
    /** The total number of completion options available. */
    total: Option[Int] = None,
    /** Indicates whether there are more completion values available beyond those in the values array. */
    hasMore: Option[Boolean] = None
) derives Codec.AsObject

// ============================================================================
// ELICITATION
// ============================================================================

enum ElicitMode derives EnumCodec {
  case form, url
}

/** A request from the server to elicit information from the user via the client. */
case class ElicitRequest(
    mode: ElicitMode,
    message: String,
    requestedSchema: Option[JsonObject] = None,
    url: Option[String] = None
) derives Codec.AsObject

enum ElicitAction derives EnumCodec {
  case accept, decline, cancel
}

case class ElicitResponse(
    action: ElicitAction,
    content: Option[JsonObject] = None,
    _meta: Option[JsonObject] = None
) derives Codec.AsObject

// ============================================================================
// NOTIFICATIONS
// ============================================================================

/** This notification can be sent by either side to indicate that it is cancelling a previously-issued request.
  */
case class CancelledNotification(
    /** The ID of the request to cancel. This MUST correspond to the ID of a request previously issued in the same direction.
      */
    requestId: RequestId,
    /** An optional string describing the reason for the cancellation. */
    reason: Option[String] = None
) derives Codec.AsObject

/** An out-of-band notification used to inform the receiver of a progress update for a long-running request.
  */
case class ProgressNotification(
    /** The progress token which was given in the initial request, used to associate this notification with the request that is proceeding.
      */
    progressToken: ProgressToken,
    /** The progress thus far. This should increase every time progress is made, even if the total is unknown.
      */
    progress: Double,
    /** Total number of items to process (or total progress required), if known. */
    total: Option[Double] = None,
    /** An optional message describing the current progress. */
    message: Option[String] = None
) derives Codec.AsObject

/** Notification from the server to the client that a resource has been updated.
  */
case class ResourceUpdatedNotification(
    /** The URI of the resource that has been updated. */
    uri: String
) derives Codec.AsObject

/** Notification from the server that the list of resources has changed.
  */
case class ResourceListChangedNotification() derives Codec.AsObject

/** Notification from the server that the list of tools has changed.
  */
case class ToolListChangedNotification() derives Codec.AsObject

/** Notification from the server that the list of prompts has changed.
  */
case class PromptListChangedNotification() derives Codec.AsObject

/** Notification from the client that its list of roots has changed.
  */
case class RootsListChangedNotification() derives Codec.AsObject
