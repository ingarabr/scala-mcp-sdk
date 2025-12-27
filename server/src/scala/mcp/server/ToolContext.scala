package mcp.server

import io.circe.{Json, JsonObject}
import mcp.protocol.{CreateMessageResult, LoggingLevel, ModelPreferences, ProgressToken, Root, SamplingMessage}

sealed trait ElicitationCapability {
  def fold[A](notSupported: => A, supported: => A): A =
    this match {
      case ElicitationCapability.NotSupported => notSupported
      case ElicitationCapability.Supported    => supported
    }

  def isSupported: Boolean = fold(notSupported = false, supported = true)
}

object ElicitationCapability {
  case object NotSupported extends ElicitationCapability
  case object Supported extends ElicitationCapability
}

sealed trait ElicitResult[+A]

object ElicitResult {
  case class Accepted[A](value: A) extends ElicitResult[A]
  case object Declined extends ElicitResult[Nothing]
  case object Cancelled extends ElicitResult[Nothing]
}

sealed trait SamplingCapability {
  def fold[A](notSupported: => A, supported: => A): A =
    this match {
      case SamplingCapability.NotSupported => notSupported
      case SamplingCapability.Supported    => supported
    }

  def isSupported: Boolean = fold(notSupported = false, supported = true)
}

object SamplingCapability {
  case object NotSupported extends SamplingCapability
  case object Supported extends SamplingCapability
}

sealed trait TasksCapability {
  def fold[A](notSupported: => A, supported: => A): A =
    this match {
      case TasksCapability.NotSupported => notSupported
      case TasksCapability.Supported    => supported
    }

  def isSupported: Boolean = fold(notSupported = false, supported = true)
}

object TasksCapability {
  case object NotSupported extends TasksCapability
  case object Supported extends TasksCapability
}

sealed trait SampleResult[+A]

object SampleResult {
  case class Success[A](value: A) extends SampleResult[A]
  case class Failed(reason: String) extends SampleResult[Nothing]
  case object NotSupported extends SampleResult[Nothing]
}

/** Execution context for tools, providing progress and logging capabilities.
  *
  * This context allows tools to:
  *   - Report progress for long-running operations
  *   - Send log messages to the client
  *   - Access request metadata
  *
  * The context is passed to tools that use the `*WithContext` variants of tool registration. Simple tools that don't need these
  * capabilities can use the regular registration methods.
  *
  * Progress notifications require the client to provide a `progressToken` in the request metadata. If no token is provided, progress
  * reports are silently ignored (no-op).
  *
  * Logging notifications are sent to the client based on the minimum log level configured via the `logging/setLevel` request. Messages
  * below the threshold are filtered by the server.
  *
  * @tparam F
  *   The effect type
  */
trait ToolContext[F[_]] {

  /** Report progress for a long-running operation.
    *
    * The progress token must be provided by the client in request metadata (`_meta.progressToken`). If no progress token is provided, this
    * method is a no-op.
    *
    * Progress values should increase monotonically. The total is optional and can be provided if known.
    *
    * Example:
    * {{{
    * for {
    *   _ <- ctx.reportProgress(0.0, Some(100.0), Some("Starting"))
    *   _ <- processPhase1()
    *   _ <- ctx.reportProgress(33.0, Some(100.0), Some("Phase 1 complete"))
    *   _ <- processPhase2()
    *   _ <- ctx.reportProgress(66.0, Some(100.0), Some("Phase 2 complete"))
    *   result <- processPhase3()
    *   _ <- ctx.reportProgress(100.0, Some(100.0), Some("Complete"))
    * } yield result
    * }}}
    *
    * @param progress
    *   Current progress value (should increase monotonically)
    * @param total
    *   Optional total value (if known)
    * @param message
    *   Optional human-readable progress message
    */
  def reportProgress(
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): F[Unit]

  /** Send a log message to the client.
    *
    * The client controls the minimum log level via the `logging/setLevel` request. Messages below the threshold are filtered by the server.
    *
    * IMPORTANT: Implementations must exclude credentials, secrets, PII, and sensitive system details from all transmitted logs.
    *
    * @param level
    *   Severity level (debug, info, notice, warning, error, critical, alert, emergency)
    * @param data
    *   Arbitrary JSON data to log
    * @param logger
    *   Optional logger name (for categorization)
    */
  def log(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): F[Unit]

  /** The progress token from the request, if provided by the client. */
  def progressToken: Option[ProgressToken]

  /** The list of roots exposed by the client, if available.
    *
    * Roots represent the operational boundaries (workspace directories/files) that the client has exposed to the server. Tools can use this
    * to:
    *   - Validate file operations are within allowed boundaries
    *   - Understand the workspace structure
    *   - Scope operations appropriately
    *
    * Returns None if:
    *   - Client doesn't support roots capability
    *   - Roots haven't been fetched yet (cache miss)
    *   - Server is not initialized
    */
  def roots: Option[List[Root]]

  /** Elicitation capability of the client. */
  def elicitationCapability: ElicitationCapability

  /** Request typed information from the user via a form.
    *
    * The client displays a form based on the fields and returns the user's input.
    *
    * Example:
    * {{{
    * val fields = (
    *   FormField.boolean.required("confirm"),
    *   FormField.string.optional("reason")
    * )
    * ctx.elicit("Delete file?", fields).map {
    *   case ElicitResult.Accepted((confirmed, reason)) => ...
    * }
    * }}}
    *
    * @param message
    *   User-facing prompt describing what information is needed
    * @param fields
    *   Tuple of FormField definitions
    * @return
    *   Accepted with extracted values, Declined, or Cancelled
    */
  def elicit[T <: Tuple](message: String, fields: T): F[ElicitResult[FormFields.ExtractTypes[T]]]

  /** Direct user to a URL for out-of-band interaction.
    *
    * Use this for sensitive operations where data must not transit through the MCP client: OAuth flows, payment processing, credential
    * collection, etc.
    *
    * IMPORTANT: This method only confirms the user navigated to the URL. The actual data exchange happens out-of-band - you must implement
    * the receiving endpoint separately (OAuth callback, webhook, etc.) and coordinate with it after receiving `Accepted`.
    *
    * @param message
    *   User-facing prompt explaining what they're authorizing
    * @param url
    *   Target URL the client will open
    * @return
    *   Accepted (user clicked through), Declined, or Cancelled
    */
  def elicitUrl(message: String, url: String): F[ElicitResult[Unit]]

  /** Sampling capability of the client. */
  def samplingCapability: SamplingCapability

  /** Tasks capability of the client. */
  def tasksCapability: TasksCapability

  /** Request an LLM completion from the client.
    *
    * Allows tools to leverage the client's LLM for text generation, analysis, or other AI-powered operations. The client controls which
    * model is used and may apply additional policies.
    *
    * Example:
    * {{{
    * val messages = List(
    *   SamplingMessage(Role.user, List(Content.Text("Summarize this code: ...")))
    * )
    * ctx.sample(messages, maxTokens = 500).map {
    *   case SampleResult.Success(result) => result.content // List[Content]
    *   case SampleResult.Failed(reason) => // Handle error
    *   case SampleResult.NotSupported => // Client doesn't support sampling
    * }
    * }}}
    *
    * @param messages
    *   Conversation history to send to the model
    * @param maxTokens
    *   Maximum tokens to generate (required)
    * @param systemPrompt
    *   Optional system prompt for the model
    * @param modelPreferences
    *   Optional hints for model selection (cost, speed, intelligence priorities)
    * @param temperature
    *   Optional sampling temperature
    * @param stopSequences
    *   Optional strings that stop generation
    * @param includeContext
    *   Context inclusion: "none", "thisServer", or "allServers"
    * @param metadata
    *   Optional metadata for the request
    * @return
    *   Success with result, Failed with reason, or NotSupported
    */
  def sample(
      messages: List[SamplingMessage],
      maxTokens: Int,
      systemPrompt: Option[String] = None,
      modelPreferences: Option[ModelPreferences] = None,
      temperature: Option[Double] = None,
      stopSequences: Option[List[String]] = None,
      includeContext: Option[String] = None,
      metadata: Option[JsonObject] = None
  ): F[SampleResult[CreateMessageResult]]
}
