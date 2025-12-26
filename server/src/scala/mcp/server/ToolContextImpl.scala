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
  CreateTaskResult,
  ElicitAction,
  ElicitMode,
  ElicitRequest,
  ElicitResponse,
  GetTaskRequest,
  GetTaskResult,
  GetTaskResultRequest,
  JsonRpcResponse,
  LoggingLevel,
  LoggingMessageNotification,
  ModelPreferences,
  ProgressNotification,
  ProgressToken,
  Root,
  SamplingMessage,
  Task,
  TaskParam,
  TaskStatus
}

import scala.concurrent.duration.*

class ToolContextImpl[F[_]: Async](
    transport: Transport[F],
    token: Option[ProgressToken],
    minLogLevel: Option[LoggingLevel],
    rootsList: Option[List[Root]],
    clientCapabilities: ClientCapabilities,
    useTasksForOutgoing: Boolean = false,
    outgoingTaskTtl: FiniteDuration = 5.minutes
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
    else {
      val useTaskAugmentation = shouldUseTasksFor(clientSupportsTasksForElicitation)
      val requestParams = if useTaskAugmentation then {
        val taskParam = TaskParam(ttl = Some(outgoingTaskTtl.toMillis))
        request.asJsonObject.add("task", taskParam.asJson)
      } else {
        request.asJsonObject
      }

      transport.sendRequest("elicitation/create", Some(requestParams)).flatMap {
        case Left(_)        => Async[F].pure(ElicitResult.Cancelled)
        case Right(jsonObj) =>
          if useTaskAugmentation && jsonObj.contains("task") then {
            Json.fromJsonObject(jsonObj).as[CreateTaskResult] match {
              case Left(_) =>
                decodeImmediateElicitResult(jsonObj)
              case Right(taskResult) =>
                pollTaskUntilComplete[ElicitResponse](
                  taskResult.task,
                  obj =>
                    Json.fromJsonObject(obj).as[ElicitResponse] match {
                      case Left(err)  => Left(s"Failed to decode elicit response: ${err.getMessage}")
                      case Right(res) => Right(res)
                    }
                ).map {
                  case Left(_)       => ElicitResult.Cancelled
                  case Right(result) => elicitResponseToResult(result)
                }
            }
          } else {
            decodeImmediateElicitResult(jsonObj)
          }
      }
    }

  private def decodeImmediateElicitResult(jsonObj: JsonObject): F[ElicitResult[JsonObject]] =
    Async[F].pure {
      Json.fromJsonObject(jsonObj).as[ElicitResponse] match {
        case Left(_)     => ElicitResult.Cancelled
        case Right(resp) => elicitResponseToResult(resp)
      }
    }

  private def elicitResponseToResult(resp: ElicitResponse): ElicitResult[JsonObject] =
    resp.action match {
      case ElicitAction.accept  => ElicitResult.Accepted(resp.content.getOrElse(JsonObject.empty))
      case ElicitAction.decline => ElicitResult.Declined
      case ElicitAction.cancel  => ElicitResult.Cancelled
    }

  def samplingCapability: SamplingCapability =
    if clientCapabilities.sampling.isDefined then SamplingCapability.Supported
    else SamplingCapability.NotSupported

  def tasksCapability: TasksCapability =
    clientCapabilities.tasks match {
      case Some(_) => TasksCapability.Supported
      case None    => TasksCapability.NotSupported
    }

  /** Check if client supports task-augmented sampling requests. */
  private def clientSupportsTasksForSampling: Boolean =
    clientCapabilities.tasks
      .flatMap(_.requests)
      .flatMap(_.sampling)
      .flatMap(_.createMessage)
      .isDefined

  /** Check if client supports task-augmented elicitation requests. */
  private def clientSupportsTasksForElicitation: Boolean =
    clientCapabilities.tasks
      .flatMap(_.requests)
      .flatMap(_.elicitation)
      .flatMap(_.create)
      .isDefined

  /** Whether to use task augmentation for this request type. */
  private def shouldUseTasksFor(clientSupports: Boolean): Boolean =
    useTasksForOutgoing && clientSupports

  /** Poll a task until it reaches a terminal state, then return the result. */
  private def pollTaskUntilComplete[A](
      task: Task,
      decodeResult: JsonObject => Either[String, A]
  ): F[Either[String, A]] = {
    val pollInterval = task.pollInterval.map(_.millis).getOrElse(1.second)
    val ttl = task.ttl.map(_.millis).getOrElse(outgoingTaskTtl)
    val deadline = System.currentTimeMillis() + ttl.toMillis

    def poll(): F[Either[String, A]] =
      for {
        now <- Async[F].delay(System.currentTimeMillis())
        result <-
          if now > deadline then Async[F].pure(Left("Task timed out waiting for completion"))
          else
            transport.sendRequest("tasks/get", Some(GetTaskRequest(task.taskId).asJsonObject)).flatMap {
              case Left(error) =>
                Async[F].pure(Left(s"Failed to poll task: ${error.message}"))
              case Right(jsonObj) =>
                Json.fromJsonObject(jsonObj).as[GetTaskResult] match {
                  case Left(decodeErr) =>
                    Async[F].pure(Left(s"Failed to decode task status: ${decodeErr.getMessage}"))
                  case Right(getResult) =>
                    getResult.task.status match {
                      case TaskStatus.completed =>
                        fetchTaskResult(task.taskId, decodeResult)
                      case TaskStatus.failed =>
                        Async[F].pure(Left(getResult.task.statusMessage.getOrElse("Task failed")))
                      case TaskStatus.cancelled =>
                        Async[F].pure(Left("Task was cancelled"))
                      case _ =>
                        Async[F].sleep(pollInterval) *> poll()
                    }
                }
            }
      } yield result

    poll()
  }

  /** Fetch the result of a completed task. */
  private def fetchTaskResult[A](
      taskId: String,
      decodeResult: JsonObject => Either[String, A]
  ): F[Either[String, A]] =
    transport.sendRequest("tasks/result", Some(GetTaskResultRequest(taskId).asJsonObject)).map {
      case Left(error)    => Left(s"Failed to fetch task result: ${error.message}")
      case Right(jsonObj) => decodeResult(jsonObj)
    }

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

      val useTaskAugmentation = shouldUseTasksFor(clientSupportsTasksForSampling)
      val requestParams = if useTaskAugmentation then {
        val taskParam = TaskParam(ttl = Some(outgoingTaskTtl.toMillis))
        request.asJsonObject.add("task", taskParam.asJson)
      } else {
        request.asJsonObject
      }

      transport.sendRequest("sampling/createMessage", Some(requestParams)).flatMap {
        case Left(error) =>
          Async[F].pure(SampleResult.Failed(error.message))
        case Right(jsonObj) =>
          // Check if response is a task or immediate result
          if useTaskAugmentation && jsonObj.contains("task") then {
            Json.fromJsonObject(jsonObj).as[CreateTaskResult] match {
              case Left(_) =>
                // Not a valid task result, try as immediate result
                decodeImmediateSampleResult(jsonObj)
              case Right(taskResult) =>
                pollTaskUntilComplete[CreateMessageResult](
                  taskResult.task,
                  obj =>
                    Json.fromJsonObject(obj).as[CreateMessageResult] match {
                      case Left(err)  => Left(s"Failed to decode sampled message: ${err.getMessage}")
                      case Right(res) => Right(res)
                    }
                ).map {
                  case Left(err)     => SampleResult.Failed(err)
                  case Right(result) => SampleResult.Success(result)
                }
            }
          } else {
            decodeImmediateSampleResult(jsonObj)
          }
      }
    }

  private def decodeImmediateSampleResult(jsonObj: JsonObject): F[SampleResult[CreateMessageResult]] =
    Async[F].pure {
      Json.fromJsonObject(jsonObj).as[CreateMessageResult] match {
        case Left(decodeError) => SampleResult.Failed(s"Failed to decode response: ${decodeError.getMessage}")
        case Right(result)     => SampleResult.Success(result)
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
      clientCapabilities: ClientCapabilities = ClientCapabilities(),
      useTasksForOutgoing: Boolean = false
  ): ToolContext[F] =
    new ToolContextImpl[F](transport, progressToken, minLogLevel, roots, clientCapabilities, useTasksForOutgoing)

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
      def tasksCapability: TasksCapability = TasksCapability.NotSupported
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
