package mcp.server

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import mcp.protocol.{
  ClientCapabilities,
  Constants,
  CreateMessageRequest,
  CreateMessageResult,
  ElicitAction,
  ElicitMode,
  ElicitRequest,
  ElicitResponse,
  JsonRpcResponse,
  LoggingLevel,
  LoggingMessageNotification,
  ModelPreferences,
  ProgressNotification,
  ProgressToken,
  Root,
  SamplingMessage
}

class ToolContextImpl[F[_]: Async](
    transport: Transport[F],
    token: Option[ProgressToken],
    minLogLevel: Option[LoggingLevel],
    rootsList: Option[List[Root]],
    clientCapabilities: ClientCapabilities
) extends ToolContext[F] {

  def reportProgress(
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): F[Unit] =
    token match {
      case Some(progressToken) =>
        val progressNotif = ProgressNotification(
          progressToken = progressToken,
          progress = progress,
          total = total,
          message = message
        )
        val notification = JsonRpcResponse.Notification(
          jsonrpc = Constants.JSONRPC_VERSION,
          method = "notifications/progress",
          params = Some(progressNotif.asJsonObject)
        )
        transport.send(notification)

      case None =>
        Async[F].unit
    }

  def log(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): F[Unit] =
    if shouldLog(level) then {
      val logNotif = LoggingMessageNotification(
        level = level,
        logger = logger,
        data = data
      )
      val notification = JsonRpcResponse.Notification(
        jsonrpc = Constants.JSONRPC_VERSION,
        method = "notifications/message",
        params = Some(logNotif.asJsonObject)
      )
      transport.send(notification)
    } else {
      Async[F].unit
    }

  def progressToken: Option[ProgressToken] = token

  def roots: Option[List[Root]] = rootsList

  def elicitationCapability: ElicitationCapability =
    if clientCapabilities.elicitation.isDefined then ElicitationCapability.Supported
    else ElicitationCapability.NotSupported

  def elicit[T <: Tuple](message: String, fields: T): F[ElicitResult[FormFields.ExtractTypes[T]]] =
    sendElicitRequest(ElicitRequest(ElicitMode.form, message, requestedSchema = Some(FormFields.toJsonObject(fields)))).map {
      case ElicitResult.Accepted(json) =>
        FormFields.extractAll(fields, json) match {
          case Right(value) => ElicitResult.Accepted(value)
          case Left(_)      => ElicitResult.Cancelled
        }
      case ElicitResult.Declined  => ElicitResult.Declined
      case ElicitResult.Cancelled => ElicitResult.Cancelled
    }

  def elicitUrl(message: String, targetUrl: String): F[ElicitResult[Unit]] =
    sendElicitRequest(ElicitRequest(ElicitMode.url, message, url = Some(targetUrl))).map {
      case ElicitResult.Accepted(_) => ElicitResult.Accepted(())
      case ElicitResult.Declined    => ElicitResult.Declined
      case ElicitResult.Cancelled   => ElicitResult.Cancelled
    }

  private def sendElicitRequest(request: ElicitRequest): F[ElicitResult[JsonObject]] =
    if !elicitationCapability.isSupported then Async[F].pure(ElicitResult.Cancelled)
    else
      transport.sendRequest("elicitation/create", Some(request.asJsonObject)).map {
        case Left(_)        => ElicitResult.Cancelled
        case Right(jsonObj) =>
          Json.fromJsonObject(jsonObj).as[ElicitResponse] match {
            case Left(_)     => ElicitResult.Cancelled
            case Right(resp) =>
              resp.action match {
                case ElicitAction.accept  => ElicitResult.Accepted(resp.content.getOrElse(JsonObject.empty))
                case ElicitAction.decline => ElicitResult.Declined
                case ElicitAction.cancel  => ElicitResult.Cancelled
              }
          }
      }

  def samplingCapability: SamplingCapability =
    if clientCapabilities.sampling.isDefined then SamplingCapability.Supported
    else SamplingCapability.NotSupported

  def sample(
      messages: List[SamplingMessage],
      maxTokens: Int,
      systemPrompt: Option[String] = None,
      modelPreferences: Option[ModelPreferences] = None,
      temperature: Option[Double] = None,
      stopSequences: Option[List[String]] = None,
      includeContext: Option[String] = None,
      metadata: Option[JsonObject] = None
  ): F[SampleResult[CreateMessageResult]] =
    if !samplingCapability.isSupported then Async[F].pure(SampleResult.NotSupported)
    else {
      val request = CreateMessageRequest(
        messages = messages,
        maxTokens = maxTokens,
        systemPrompt = systemPrompt,
        modelPreferences = modelPreferences,
        temperature = temperature,
        stopSequences = stopSequences,
        includeContext = includeContext,
        metadata = metadata
      )
      transport.sendRequest("sampling/createMessage", Some(request.asJsonObject)).map {
        case Left(error)    => SampleResult.Failed(error.message)
        case Right(jsonObj) =>
          Json.fromJsonObject(jsonObj).as[CreateMessageResult] match {
            case Left(decodeError) => SampleResult.Failed(s"Failed to decode response: ${decodeError.getMessage}")
            case Right(result)     => SampleResult.Success(result)
          }
      }
    }

  private def shouldLog(level: LoggingLevel): Boolean =
    minLogLevel match {
      case None           => true
      case Some(minLevel) => level.ordinal >= minLevel.ordinal
    }
}

object ToolContextImpl {

  def apply[F[_]: Async](
      transport: Transport[F],
      progressToken: Option[ProgressToken],
      minLogLevel: Option[LoggingLevel] = None,
      roots: Option[List[Root]] = None,
      clientCapabilities: ClientCapabilities = ClientCapabilities()
  ): ToolContext[F] =
    new ToolContextImpl[F](transport, progressToken, minLogLevel, roots, clientCapabilities)

  def noop[F[_]: Async]: ToolContext[F] =
    new ToolContext[F] {
      def reportProgress(progress: Double, total: Option[Double], message: Option[String]): F[Unit] = Async[F].unit
      def log(level: LoggingLevel, data: Json, logger: Option[String]): F[Unit] = Async[F].unit
      def progressToken: Option[ProgressToken] = None
      def roots: Option[List[Root]] = None
      def elicitationCapability: ElicitationCapability = ElicitationCapability.NotSupported
      def elicit[T <: Tuple](message: String, fields: T): F[ElicitResult[FormFields.ExtractTypes[T]]] =
        Async[F].pure(ElicitResult.Cancelled)
      def elicitUrl(message: String, url: String): F[ElicitResult[Unit]] = Async[F].pure(ElicitResult.Cancelled)
      def samplingCapability: SamplingCapability = SamplingCapability.NotSupported
      def sample(
          messages: List[SamplingMessage],
          maxTokens: Int,
          systemPrompt: Option[String],
          modelPreferences: Option[ModelPreferences],
          temperature: Option[Double],
          stopSequences: Option[List[String]],
          includeContext: Option[String],
          metadata: Option[JsonObject]
      ): F[SampleResult[CreateMessageResult]] =
        Async[F].pure(SampleResult.NotSupported)
    }
}
