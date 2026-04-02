package mcp.server

import cats.effect.{Async, Fiber, Ref, Resource as CatsResource}
import cats.effect.std.Queue
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.*

import scala.concurrent.duration.*

/** MCP server configuration with primitives defined as data structures.
  *
  * This is the main interface for building MCP servers. Define your tools, resources, and prompts as data structures and pass them to the
  * server constructor.
  *
  * Usage:
  * {{{
  * val echoTool = ToolDef[IO](
  *   name = "echo",
  *   description = Some("Echo back the input"),
  *   inputSchema = ...,
  *   handler = args => IO.pure(CallToolResult(...))
  * )
  *
  * val configResource = ResourceDef[IO](
  *   uri = "file://config.json",
  *   name = "Configuration",
  *   description = Some("Server config"),
  *   mimeType = Some("application/json"),
  *   handler = () => IO.pure(List(...))
  * )
  *
  * McpServer[IO](
  *   info = Implementation("my-server", "1.0.0"),
  *   tools = List(echoTool),
  *   resources = List(configResource),
  *   prompts = Nil
  * ).use { server =>
  *   StdioTransport[IO]().use(transport => server.serve(transport))
  * }
  * }}}
  */
trait McpServer[F[_]] {

  /** Start the server with the given transport.
    *
    * This will:
    *   - Process incoming requests from the transport
    *   - Route them to registered handlers
    *   - Send responses back through the transport
    *   - Handle the initialization handshake
    *
    * The returned Resource manages the server's lifecycle
    *
    * @param transport
    *   The transport to use for communication
    * @return
    *   Resource that manages the server lifecycle
    */
  def serve(transport: Transport[F]): CatsResource[F, Unit]

  /** Get the server's capabilities based on registered primitives */
  def capabilities: ServerCapabilities

  /** Get the server implementation info */
  def info: Implementation

  /** Notify subscribed clients that a resource has been updated.
    *
    * Only sends notification if the URI has active subscriptions. No-op if not serving.
    *
    * @param uri
    *   The URI of the updated resource
    */
  def notifyResourceUpdated(uri: ResourceUri): F[Unit]

  /** Notify clients that the resource list has changed.
    *
    * Sends notification to all connected clients regardless of subscriptions. Use when resources are added, removed, or their metadata
    * changes.
    */
  def notifyResourceListChanged(): F[Unit]

  /** Notify clients that the tool list has changed.
    *
    * Sends notification to all connected clients. Use when tools are added, removed, or their metadata changes.
    */
  def notifyToolListChanged(): F[Unit]

  /** Notify clients that the prompt list has changed.
    *
    * Sends notification to all connected clients. Use when prompts are added, removed, or their metadata changes.
    */
  def notifyPromptListChanged(): F[Unit]

  /** Add tools dynamically. Notifies connected clients of the change. */
  def addTools(tools: List[ToolDef[F, _, _]]): F[Unit]

  /** Remove tools by name. Notifies connected clients of the change. */
  def removeTools(names: List[String]): F[Unit]

  /** Add resources dynamically. Notifies connected clients of the change. */
  def addResources(resources: List[ResourceDef[F, _]]): F[Unit]

  /** Remove resources by URI. Notifies connected clients of the change. */
  def removeResources(uris: List[String]): F[Unit]

  /** Add resource templates dynamically. Notifies connected clients of the change. */
  def addResourceTemplates(templates: List[ResourceTemplateDef[F]]): F[Unit]

  /** Remove resource templates by URI template. Notifies connected clients of the change. */
  def removeResourceTemplates(uriTemplates: List[String]): F[Unit]

  /** Add prompts dynamically. Notifies connected clients of the change. */
  def addPrompts(prompts: List[PromptDef[F, _]]): F[Unit]

  /** Remove prompts by name. Notifies connected clients of the change. */
  def removePrompts(names: List[String]): F[Unit]

  /** Add completion providers dynamically. */
  def addCompletions(completions: List[CompletionDef[F]]): F[Unit]

  /** Remove completion providers by reference. */
  def removeCompletions(refs: List[CompletionReference]): F[Unit]
}
object McpServer {

  /** Default timeout for graceful shutdown. */
  val DefaultShutdownTimeout: FiniteDuration = 30.seconds

  /** Create a new MCP server with pre-defined primitives.
    *
    * @param info
    *   Server name and version
    * @param tools
    *   List of tool definitions (existential types)
    * @param resources
    *   List of resource definitions (existential types)
    * @param resourceTemplates
    *   List of resource template definitions for dynamic resources
    * @param prompts
    *   List of prompt definitions (existential types)
    * @param completions
    *   List of completion providers for prompts and resource templates
    * @param paginationConfig
    *   Configuration for list pagination (page size)
    * @param tasksEnabled
    *   Whether to enable async task support for incoming requests (tools/call with task param)
    * @param taskConfig
    *   Configuration for task TTL and polling behavior (only used when tasksEnabled=true)
    * @param useTasksForOutgoingRequests
    *   Whether to use task augmentation for outgoing requests (sampling/elicitation). When enabled, the server will add task params to
    *   outgoing requests if the client supports tasks, and poll for completion. Requires tasksEnabled=true.
    * @param shutdownTimeout
    *   Maximum time to wait for running tasks during graceful shutdown
    * @return
    *   Resource managing the server lifecycle
    */
  def apply[F[_]: Async](
      info: Implementation,
      instructions: Option[String] = None,
      tools: List[ToolDef[F, _, _]] = Nil,
      resources: List[ResourceDef[F, _]] = Nil,
      resourceTemplates: List[ResourceTemplateDef[F]] = Nil,
      prompts: List[PromptDef[F, _]] = Nil,
      completions: List[CompletionDef[F]] = Nil,
      paginationConfig: PaginationConfig = PaginationConfig.Default,
      tasksEnabled: Boolean = false,
      taskConfig: TaskConfig = TaskConfig(),
      useTasksForOutgoingRequests: Boolean = false,
      shutdownTimeout: FiniteDuration = DefaultShutdownTimeout
  ): CatsResource[F, McpServer[F]] =
    CatsResource.eval {
      for {
        toolsRef <- Ref.of[F, Map[String, ToolDef[F, _, _]]](tools.map(t => t.name -> t).toMap)
        resourcesRef <- Ref.of[F, Map[String, ResourceDef[F, _]]](resources.map(r => r.uri -> r).toMap)
        resourceTemplatesRef <- Ref.of[F, List[ResourceTemplateDef[F]]](resourceTemplates)
        promptsRef <- Ref.of[F, Map[String, PromptDef[F, _]]](prompts.map(p => p.name -> p).toMap)
        completionsRef <- Ref.of[F, List[CompletionDef[F]]](completions)
        resourceUpdateQueue <- Queue.unbounded[F, ResourceUri]
        connectionState <- Ref.of[F, ConnectionState](ConnectionState.Uninitialized)
        activeTransport <- Ref.of[F, Option[Transport[F]]](None)
        inFlightRequests <- Ref.of[F, Map[RequestId, Fiber[F, Throwable, Option[JsonRpcResponse]]]](Map.empty)
        initRequestId <- Ref.of[F, Option[RequestId]](None)
        taskRegistry <- if tasksEnabled then TaskRegistry[F](taskConfig).map(Some(_)) else Async[F].pure(None)
      } yield new McpServerImpl[F](
        serverInfo = info,
        instructions = instructions,
        toolsRef = toolsRef,
        resourcesRef = resourcesRef,
        resourceTemplatesRef = resourceTemplatesRef,
        promptsRef = promptsRef,
        completionsRef = completionsRef,
        resourceUpdateQueue = resourceUpdateQueue,
        paginationConfig = paginationConfig,
        connectionState = connectionState,
        activeTransport = activeTransport,
        inFlightRequests = inFlightRequests,
        initRequestId = initRequestId,
        taskRegistry = taskRegistry,
        taskConfig = taskConfig,
        useTasksForOutgoing = useTasksForOutgoingRequests && tasksEnabled,
        shutdownTimeout = shutdownTimeout
      )
    }
}

/** Implementation of the MCP server.
  *
  * This handles:
  *   - Request routing to primitive handlers
  *   - Initialization handshake
  *   - Error handling
  */
private class McpServerImpl[F[_]: Async](
    val serverInfo: Implementation,
    instructions: Option[String],
    toolsRef: Ref[F, Map[String, ToolDef[F, _, _]]],
    resourcesRef: Ref[F, Map[String, ResourceDef[F, _]]],
    resourceTemplatesRef: Ref[F, List[ResourceTemplateDef[F]]],
    promptsRef: Ref[F, Map[String, PromptDef[F, _]]],
    completionsRef: Ref[F, List[CompletionDef[F]]],
    resourceUpdateQueue: Queue[F, ResourceUri],
    paginationConfig: PaginationConfig,
    connectionState: Ref[F, ConnectionState],
    activeTransport: Ref[F, Option[Transport[F]]],
    inFlightRequests: Ref[F, Map[RequestId, Fiber[F, Throwable, Option[JsonRpcResponse]]]],
    initRequestId: Ref[F, Option[RequestId]],
    taskRegistry: Option[TaskRegistry[F]],
    taskConfig: TaskConfig,
    useTasksForOutgoing: Boolean,
    shutdownTimeout: FiniteDuration
) extends McpServer[F] {

  def info: Implementation = serverInfo

  /** Always declare all capabilities with listChanged since primitives can be added dynamically. */
  def capabilities: ServerCapabilities =
    ServerCapabilities(
      tools = Some(ToolsCapability(listChanged = Some(true))),
      resources = Some(ResourcesCapability(subscribe = Some(true), listChanged = Some(true))),
      prompts = Some(PromptsCapability(listChanged = Some(true))),
      completions = Some(JsonObject.empty),
      tasks = taskRegistry.map(_ =>
        TasksCapability(
          list = Some(JsonObject.empty),
          cancel = Some(JsonObject.empty),
          requests = Some(ServerTasksRequests(tools = Some(ServerTasksTools(call = Some(JsonObject.empty)))))
        )
      )
    )

  /** Start resource update stream piping into the central queue. */
  private def startResourceUpdateStreams: F[Unit] =
    for {
      resources <- resourcesRef.get
      templates <- resourceTemplatesRef.get
      staticStreams = resources.values.toList.map(r => r.updates.as(ResourceUri(r.uri)))
      templateStreams = templates.map(_.updates)
      allStreams = staticStreams ++ templateStreams
      _ <-
        if allStreams.nonEmpty then
          Async[F]
            .start(
              fs2.Stream.emits(allStreams).parJoinUnbounded.evalMap(resourceUpdateQueue.offer).compile.drain
            )
            .void
        else Async[F].unit
    } yield ()

  def serve(transport: Transport[F]): CatsResource[F, Unit] = {
    // Main message handling stream
    val messageStream = transport.receive
      .evalMap(handleMessage(_, transport))
      .collect { case Some(msg) => msg }
      .foreach(response => transport.send(response))

    // Resource updates stream — reads from the central queue
    val updatesStream = fs2.Stream.fromQueueUnterminated(resourceUpdateQueue).evalMap(notifyResourceUpdated)

    // Combined stream that processes messages and handles resource updates
    val combinedStream = messageStream.concurrently(updatesStream)

    CatsResource
      .make(
        // Acquire: set transport, start resource update streams, start message processing
        activeTransport.set(Some(transport)) *>
          startResourceUpdateStreams *>
          Async[F].start(combinedStream.compile.drain).map(_.cancel)
      ) { cancelStream =>
        // Release: graceful shutdown
        for {
          // 1. Transition to Shutdown state - prevents new requests from being processed
          _ <- connectionState.set(ConnectionState.Shutdown)

          // 2. Cancel in-flight request fibers
          inFlight <- inFlightRequests.getAndSet(Map.empty)
          _ <- inFlight.values.toList.traverse_(_.cancel)

          // 3. Wait for running tasks with hard timeout (if tasks enabled)
          _ <- taskRegistry.traverse_(registry => registry.awaitAllTasks(shutdownTimeout))

          // 4. Cancel the message processing stream
          _ <- cancelStream

          // 5. Clear the transport reference
          _ <- activeTransport.set(None)
        } yield ()
      }
      .void
  }

  /** Handle an incoming JSON-RPC request and optionally generate a response.
    *
    * Returns None for notifications (which should not receive responses per JSON-RPC spec).
    */
  private def handleMessage(message: JsonRpcRequest, transport: Transport[F]): F[Option[JsonRpcResponse]] =
    message match {
      case JsonRpcRequest.Request(jsonrpc, id, method, params) =>
        // Record initialize request ID so it cannot be cancelled (per spec)
        val recordInit = if method == "initialize" then initRequestId.set(Some(id)) else Async[F].unit
        recordInit *> trackRequest(id) {
          handleRequest(method, params, transport)
            .map {
              case Right(result) =>
                Some(JsonRpcResponse.Response(jsonrpc, id, result))
              case Left(errorData) =>
                Some(JsonRpcResponse.Error(jsonrpc, Some(id), errorData))
            }
            .handleError { error =>
              // Catch-all for unexpected exceptions
              Some(
                JsonRpcResponse.Error(
                  jsonrpc,
                  Some(id),
                  McpError.internalError(error.getMessage)
                )
              )
            }
        }

      case JsonRpcRequest.Notification(_, method, params) =>
        // Notifications don't get responses per JSON-RPC 2.0 spec
        handleNotification(method, params, transport).as(None)
    }

  /** Execute a request with fiber tracking for cancellation support.
    *
    * Registers the fiber in the in-flight map before starting, and ensures cleanup happens regardless of how the fiber completes (success,
    * error, or cancellation).
    */
  private def trackRequest(requestId: RequestId)(handler: F[Option[JsonRpcResponse]]): F[Option[JsonRpcResponse]] =
    for {
      fiber <- Async[F].start(handler)
      result <- Async[F].bracketCase(inFlightRequests.update(_ + (requestId -> fiber)))(_ => fiber.joinWithNever)((_, _) =>
        inFlightRequests.update(_ - requestId)
      )
    } yield result

  /** Extract capabilities from connection state and pass to handler.
    *
    * Returns error if server is not initialized.
    */
  private def withCapabilities(
      handler: ClientCapabilities => F[Either[ErrorData, JsonObject]]
  ): F[Either[ErrorData, JsonObject]] =
    connectionState.get.flatMap { state =>
      state.clientCapabilities match {
        case Some(caps) =>
          handler(caps)
        case None =>
          McpError.internalError("Server not initialized").asError
      }
    }

  /** Handle a request and return either an error or the result */
  private def handleRequest(method: String, params: Option[JsonObject], transport: Transport[F]): F[Either[ErrorData, JsonObject]] =
    method match {
      case "initialize" =>
        handleInitialize(params)

      case "ping" =>
        handlePing()

      case "tools/list" =>
        withCapabilities(_ => handleListTools(params))

      case "tools/call" =>
        withCapabilities(caps => handleCallTool(params, transport, caps))

      case "resources/list" =>
        withCapabilities(_ => handleListResources(params))

      case "resources/templates/list" =>
        withCapabilities(_ => handleListResourceTemplates(params))

      case "resources/read" =>
        withCapabilities(caps => handleReadResource(params, transport, caps))

      case "resources/subscribe" =>
        withCapabilities(_ => handleSubscribe(params, transport))

      case "resources/unsubscribe" =>
        withCapabilities(_ => handleUnsubscribe(params))

      case "prompts/list" =>
        withCapabilities(_ => handleListPrompts(params))

      case "prompts/get" =>
        withCapabilities(caps => handleGetPrompt(params, caps))

      case "logging/setLevel" =>
        withCapabilities(_ => handleSetLevel(params))

      case "completion/complete" =>
        withCapabilities(_ => handleComplete(params))

      case "tasks/list" =>
        withCapabilities(_ => handleListTasks(params))

      case "tasks/get" =>
        withCapabilities(_ => handleGetTask(params))

      case "tasks/cancel" =>
        withCapabilities(_ => handleCancelTask(params))

      case "tasks/result" =>
        withCapabilities(_ => handleGetTaskResult(params))

      case _ =>
        McpError.methodNotFound(s"Method not found: $method").asError
    }

  private def fetchRoots(transport: Transport[F]): F[Unit] =
    connectionState.get.flatMap {
      case ConnectionState.Initialized(caps, logLevel, _, subs) =>
        caps.roots match {
          case Some(_) =>
            transport.sendRequest("roots/list", None).flatMap {
              case Right(resultObj) =>
                resultObj.asJson.as[ListRootsResult] match {
                  case Right(result) =>
                    connectionState.set(ConnectionState.Initialized(caps, logLevel, Some(result.roots), subs))
                  case Left(_) =>
                    connectionState.set(ConnectionState.Initialized(caps, logLevel, None, subs))
                }
              case Left(_) =>
                connectionState.set(ConnectionState.Initialized(caps, logLevel, None, subs))
            }
          case None => Async[F].unit
        }
      case ConnectionState.Operational(caps, logLevel, _, subs) =>
        caps.roots match {
          case Some(_) =>
            transport.sendRequest("roots/list", None).flatMap {
              case Right(resultObj) =>
                resultObj.asJson.as[ListRootsResult] match {
                  case Right(result) =>
                    connectionState.set(ConnectionState.Operational(caps, logLevel, Some(result.roots), subs))
                  case Left(_) =>
                    connectionState.set(ConnectionState.Operational(caps, logLevel, None, subs))
                }
              case Left(_) =>
                connectionState.set(ConnectionState.Operational(caps, logLevel, None, subs))
            }
          case None => Async[F].unit
        }
      case _ => Async[F].unit
    }

  /** Handle notifications (fire and forget) */
  private def handleNotification(method: String, params: Option[JsonObject], transport: Transport[F]): F[Unit] =
    method match {
      case "notifications/initialized" =>
        // Transition from Initialized to Operational state, then fetch roots if client supports them
        connectionState.get.flatMap {
          case ConnectionState.Initialized(caps, minLogLevel, roots, subs) =>
            connectionState.set(ConnectionState.Operational(caps, minLogLevel, roots, subs)) *>
              fetchRoots(transport)
          case _ =>
            // Already operational or not yet initialized - ignore
            Async[F].unit
        }

      case "notifications/cancelled" =>
        handleCancelled(params)

      case "notifications/roots/list_changed" =>
        // Client notified us that roots changed - fetch fresh roots
        fetchRoots(transport)

      case _ =>
        // Unknown notification, ignore
        Async[F].unit
    }

  /** Handle a cancellation notification by cancelling the in-flight request fiber.
    *
    * Cancellation is best-effort: if the request has already completed or doesn't exist, this is a no-op.
    */
  private def handleCancelled(params: Option[JsonObject]): F[Unit] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[CancelledNotification] match {
      case Right(notification) =>
        notification.requestId match {
          case Some(requestId) =>
            // Per spec: initialize requests MUST NOT be cancelled — silently ignore
            initRequestId.get.flatMap {
              case Some(initId) if initId == requestId => Async[F].unit
              case _                                   => inFlightRequests.get.flatMap(_.get(requestId).fold(Async[F].unit)(_.cancel))
            }
          case None =>
            // No requestId - for task cancellation, use tasks/cancel instead
            Async[F].unit
        }
      case Left(_) =>
        // Malformed notification - ignore (notifications don't get error responses)
        Async[F].unit
    }
  }

  // Request handlers

  private def handleInitialize(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[InitializeRequest] match {
      case Right(request) =>
        connectionState
          .set(ConnectionState.Initialized(request.capabilities))
          .as(
            Right(
              InitializeResult(
                protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
                capabilities = capabilities,
                serverInfo = info,
                instructions = instructions
              ).asJsonObject
            )
          )

      case Left(error) =>
        McpError.invalidParams(s"Invalid initialize request: ${error.getMessage}").asError
    }
  }

  /** Handle ping request - respond immediately with empty result */
  private def handlePing(): F[Either[ErrorData, JsonObject]] =
    Async[F].pure(Right(EmptyResult().asJsonObject))

  /** Handle logging/setLevel request - configure minimum log level for the connection.
    *
    * This can only be called after initialization (Initialized or Operational state). Updates the connection state to include the new
    * minimum log level.
    */
  private def handleSetLevel(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[SetLevelRequest] match {
      case Right(request) =>
        connectionState.get.flatMap {
          case ConnectionState.Initialized(caps, _, roots, subs) =>
            connectionState.set(ConnectionState.Initialized(caps, Some(request.level), roots, subs)).as(Right(EmptyResult().asJsonObject))
          case ConnectionState.Operational(caps, _, roots, subs) =>
            connectionState.set(ConnectionState.Operational(caps, Some(request.level), roots, subs)).as(Right(EmptyResult().asJsonObject))

          case _ =>
            // Not initialized yet - this shouldn't happen in practice as withCapabilities should guard it,
            // but we handle it gracefully
            McpError.internalError("Cannot set log level before initialization").asError
        }

      case Left(error) =>
        McpError.invalidParams(s"Invalid logging/setLevel request: ${error.getMessage}").asError
    }
  }

  private def handleListTools(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] =
    toolsRef.get.map { toolsMap =>
      val cursor = params.flatMap(_("cursor")).flatMap(_.asString)
      val allTools = toolsMap.values.map(_.toTool).toList
      Paginator.paginate(allTools, cursor, paginationConfig, _.name) match {
        case Left(error)      => Left(error)
        case Right(paginated) =>
          Right(ListToolsResult(tools = paginated.items, nextCursor = paginated.nextCursor).asJsonObject)
      }
    }

  private def handleCallTool(
      params: Option[JsonObject],
      transport: Transport[F],
      capabilities: ClientCapabilities
  ): F[Either[ErrorData, JsonObject]] = {
    val paramsObj = params.getOrElse(JsonObject.empty)
    val paramsJson = paramsObj.asJson

    // Extract progress token from _meta.progressToken if present
    val progressToken: Option[ProgressToken] = paramsObj("_meta")
      .flatMap(_.asObject)
      .flatMap(_("progressToken"))
      .flatMap(_.as[ProgressToken].toOption)

    // Extract task param for task-augmented requests
    val taskParamOpt: Option[TaskParam] = paramsObj("task")
      .flatMap(_.as[TaskParam].toOption)

    (connectionState.get, toolsRef.get).flatMapN { (state, toolsMap) =>
      val context = ToolContextImpl[F](transport, progressToken, state.minLogLevel, state.rootsList, capabilities, useTasksForOutgoing)

      paramsJson.as[CallToolRequest] match {
        case Right(request) =>
          toolsMap.get(request.name) match {
            case Some(toolDef) =>
              (taskParamOpt, taskRegistry) match {
                // Task param present and server supports tasks — enforce per-tool taskMode
                case (Some(taskParam), Some(registry)) =>
                  toolDef.taskMode match {
                    case TaskMode.SyncOnly =>
                      McpError.methodNotFound(s"Tool '${request.name}' does not support task-augmented execution").asError
                    case _ =>
                      handleCallToolAsTask(request, toolDef, context, registry, taskParam)
                  }
                // No task param — enforce AsyncOnly
                case (None, _) =>
                  toolDef.taskMode match {
                    case TaskMode.AsyncOnly =>
                      McpError.methodNotFound(s"Tool '${request.name}' requires task-augmented execution").asError
                    case _ =>
                      toolDef.execute(request.arguments, Some(context)).map(result => Right(result.asJsonObject))
                  }
                // Task param present but server doesn't support tasks — ignore task param per spec
                case (Some(_), None) =>
                  toolDef.execute(request.arguments, Some(context)).map(result => Right(result.asJsonObject))
              }
            case None =>
              McpError.invalidParams(s"Tool not found: ${request.name}").asError
          }

        case Left(error) =>
          McpError.invalidParams(s"Invalid tool call request: ${error.getMessage}").asError
      }
    }
  }

  private def handleCallToolAsTask(
      request: CallToolRequest,
      toolDef: ToolDef[F, ?, ?],
      context: ToolContext[F],
      registry: TaskRegistry[F],
      taskParam: TaskParam
  ): F[Either[ErrorData, JsonObject]] =
    for {
      task <- registry.create(taskParam.ttl.map(_.millis))
      fiber <- Async[F].start {
        toolDef
          .execute(request.arguments, Some(context))
          .flatMap { result =>
            val status = if result.isError.contains(true) then TaskStatus.failed else TaskStatus.completed
            val statusMessage =
              if result.isError.contains(true) then result.content.collectFirst { case Content.Text(text, _, _) => text } else None
            registry.storeResult(task.taskId, result.asJson) *>
              registry.updateStatus(task.taskId, status, statusMessage)
          }
          .handleErrorWith { error =>
            registry.updateStatus(task.taskId, TaskStatus.failed, Some(error.getMessage))
          }
      }
      _ <- registry.registerFiber(task.taskId, fiber)
    } yield Right(CreateTaskResult(task = task).asJsonObject)

  private def handleListResources(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] =
    resourcesRef.get.map { resourcesMap =>
      val cursor = params.flatMap(_("cursor")).flatMap(_.asString)
      val allResources = resourcesMap.values.map(_.toResource).toList
      Paginator.paginate(allResources, cursor, paginationConfig, _.uri) match {
        case Left(error)      => Left(error)
        case Right(paginated) =>
          Right(ListResourcesResult(resources = paginated.items, nextCursor = paginated.nextCursor).asJsonObject)
      }
    }

  private def handleListResourceTemplates(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] =
    resourceTemplatesRef.get.map { templates =>
      val cursor = params.flatMap(_("cursor")).flatMap(_.asString)
      val allTemplates = templates.map(_.toResourceTemplate)
      Paginator.paginate(allTemplates, cursor, paginationConfig, _.uriTemplate) match {
        case Left(error)      => Left(error)
        case Right(paginated) =>
          Right(ListResourceTemplatesResult(resourceTemplates = paginated.items, nextCursor = paginated.nextCursor).asJsonObject)
      }
    }

  private def handleReadResource(
      params: Option[JsonObject],
      transport: Transport[F],
      capabilities: ClientCapabilities
  ): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[ReadResourceRequest] match {
      case Right(request) =>
        // First check static resources (exact match)
        resourcesRef.get.flatMap { resourcesMap =>
          resourcesMap.get(request.uri) match {
            case Some(resourceDef) =>
              connectionState.get.flatMap { state =>
                val context = ResourceContextImpl[F](transport, state.minLogLevel, state.rootsList)
                resourceDef.read(context).map(_.map(_.asJsonObject))
              }
            case None =>
              // No static match - try templates
              resolveFromTemplates(request.uri, transport)
          }
        }

      case Left(error) =>
        McpError.invalidParams(s"Invalid read resource request: ${error.getMessage}").asError
    }
  }

  /** Try to resolve a URI using resource templates.
    *
    * Templates are checked in order; the first matching template that resolves to a ResourceDef is used.
    */
  private def resolveFromTemplates(uri: String, transport: Transport[F]): F[Either[ErrorData, JsonObject]] =
    (connectionState.get, resourceTemplatesRef.get).flatMapN { (state, templates) =>
      val context = ResourceContextImpl[F](transport, state.minLogLevel, state.rootsList)

      def tryTemplates(remaining: List[ResourceTemplateDef[F]]): F[Either[ErrorData, JsonObject]] =
        remaining match {
          case Nil =>
            McpError.resourceNotFound(s"Resource not found: $uri").asError
          case template :: rest =>
            if template.matches(uri) then {
              template.resolve(uri, context).flatMap {
                case Some(resourceDef) =>
                  resourceDef.read(context).map(_.map(_.asJsonObject))
                case None =>
                  tryTemplates(rest)
              }
            } else {
              tryTemplates(rest)
            }
        }

      tryTemplates(templates)
    }

  private def validateResourceExists(uri: ResourceUri, transport: Transport[F]): F[Boolean] =
    resourcesRef.get.flatMap { resourcesMap =>
      if resourcesMap.contains(uri.value) then Async[F].pure(true)
      else
        (connectionState.get, resourceTemplatesRef.get).flatMapN { (state, templates) =>
          val context = ResourceContextImpl[F](transport, state.minLogLevel, state.rootsList)

          def tryTemplates(remaining: List[ResourceTemplateDef[F]]): F[Boolean] =
            remaining match {
              case Nil              => Async[F].pure(false)
              case template :: rest =>
                if template.matches(uri.value) then {
                  template.resolve(uri.value, context).flatMap {
                    case Some(_) => Async[F].pure(true)
                    case None    => tryTemplates(rest)
                  }
                } else {
                  tryTemplates(rest)
                }
            }

          tryTemplates(templates)
        }
    }

  private def handleSubscribe(
      params: Option[JsonObject],
      transport: Transport[F]
  ): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[SubscribeRequest] match {
      case Right(request) =>
        val uri = ResourceUri(request.uri)
        validateResourceExists(uri, transport).flatMap {
          case true =>
            addSubscription(uri).as(Right(EmptyResult().asJsonObject))
          case false =>
            McpError.resourceNotFound(s"Resource not found: ${request.uri}").asError
        }

      case Left(error) =>
        McpError.invalidParams(s"Invalid subscribe request: ${error.getMessage}").asError
    }
  }

  private def handleUnsubscribe(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[UnsubscribeRequest] match {
      case Right(request) =>
        removeSubscription(ResourceUri(request.uri)).as(Right(EmptyResult().asJsonObject))

      case Left(error) =>
        McpError.invalidParams(s"Invalid unsubscribe request: ${error.getMessage}").asError
    }
  }

  private def addSubscription(uri: ResourceUri): F[Unit] =
    connectionState.update {
      case ConnectionState.Initialized(caps, logLevel, roots, subs) =>
        ConnectionState.Initialized(caps, logLevel, roots, subs + uri)
      case ConnectionState.Operational(caps, logLevel, roots, subs) =>
        ConnectionState.Operational(caps, logLevel, roots, subs + uri)
      case other => other
    }

  private def removeSubscription(uri: ResourceUri): F[Unit] =
    connectionState.update {
      case ConnectionState.Initialized(caps, logLevel, roots, subs) =>
        ConnectionState.Initialized(caps, logLevel, roots, subs - uri)
      case ConnectionState.Operational(caps, logLevel, roots, subs) =>
        ConnectionState.Operational(caps, logLevel, roots, subs - uri)
      case other => other
    }

  private def handleListPrompts(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] =
    promptsRef.get.map { promptsMap =>
      val cursor = params.flatMap(_("cursor")).flatMap(_.asString)
      val allPrompts = promptsMap.values.map(_.toPrompt).toList
      Paginator.paginate(allPrompts, cursor, paginationConfig, _.name) match {
        case Left(error)      => Left(error)
        case Right(paginated) =>
          Right(ListPromptsResult(prompts = paginated.items, nextCursor = paginated.nextCursor).asJsonObject)
      }
    }

  private def handleGetPrompt(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[GetPromptRequest] match {
      case Right(request) =>
        promptsRef.get.flatMap { promptsMap =>
          promptsMap.get(request.name) match {
            case Some(promptDef) =>
              promptDef.get(request.arguments).map(result => Right(result.asJsonObject))
            case None =>
              McpError.invalidParams(s"Prompt not found: ${request.name}").asError
          }
        }

      case Left(error) =>
        McpError.invalidParams(s"Invalid get prompt request: ${error.getMessage}").asError
    }
  }

  private def handleComplete(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[CompleteRequest] match {
      case Right(request) =>
        completionsRef.get.flatMap { completions =>
          findCompletionProvider(completions, request.ref) match {
            case Some(provider) =>
              provider.complete(request.argument, request.context).map(result => Right(result.asJsonObject))
            case None =>
              Async[F].pure(Right(CompleteResult(completion = CompletionCompletion(values = Nil)).asJsonObject))
          }
        }

      case Left(error) =>
        McpError.invalidParams(s"Invalid completion request: ${error.getMessage}").asError
    }
  }

  private def findCompletionProvider(completions: List[CompletionDef[F]], ref: CompletionReference): Option[CompletionDef[F]] =
    completions.find { provider =>
      (provider.ref, ref) match {
        case (CompletionReference.Prompt(name1, _), CompletionReference.Prompt(name2, _)) =>
          name1 == name2
        case (CompletionReference.ResourceTemplate(uri1), CompletionReference.ResourceTemplate(uri2)) =>
          uri1 == uri2
        case _ =>
          false
      }
    }

  // ============================================================================
  // TASK HANDLERS
  // ============================================================================

  private def handleListTasks(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] =
    taskRegistry match {
      case Some(registry) =>
        registry.list().map { allTasks =>
          val cursor = params.flatMap(_("cursor")).flatMap(_.asString)
          Paginator.paginate(allTasks, cursor, paginationConfig, _.taskId) match {
            case Left(error)      => Left(error)
            case Right(paginated) =>
              Right(ListTasksResult(tasks = paginated.items, nextCursor = paginated.nextCursor).asJsonObject)
          }
        }
      case None =>
        McpError.methodNotFound("Tasks not enabled").asError
    }

  private def handleGetTask(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[GetTaskRequest] match {
      case Right(request) =>
        taskRegistry match {
          case Some(registry) =>
            registry.get(request.taskId).map {
              case Some(task) => Right(GetTaskResult(task = task).asJsonObject)
              case None       => Left(McpError.invalidParams(s"Task not found: ${request.taskId}"))
            }
          case None =>
            McpError.methodNotFound("Tasks not enabled").asError
        }
      case Left(error) =>
        McpError.invalidParams(s"Invalid request: ${error.getMessage}").asError
    }
  }

  private def handleCancelTask(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[CancelTaskRequest] match {
      case Right(request) =>
        taskRegistry match {
          case Some(registry) =>
            registry.cancel(request.taskId).map {
              case Some(task) => Right(CancelTaskResult(task = task).asJsonObject)
              case None       =>
                Left(McpError.invalidParams(s"Cannot cancel task: ${request.taskId}"))
            }
          case None =>
            McpError.methodNotFound("Tasks not enabled").asError
        }
      case Left(error) =>
        McpError.invalidParams(s"Invalid request: ${error.getMessage}").asError
    }
  }

  private def handleGetTaskResult(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[GetTaskResultRequest] match {
      case Right(request) =>
        taskRegistry match {
          case Some(registry) =>
            val pollInterval = taskConfig.defaultPollInterval

            def awaitTerminal: F[Task] =
              registry.get(request.taskId).flatMap {
                case Some(task) if task.status.isTerminal => Async[F].pure(task)
                case Some(_)                              => Async[F].sleep(pollInterval) *> awaitTerminal
                case None                                 =>
                  Async[F].raiseError(new NoSuchElementException(s"Task not found: ${request.taskId}"))
              }

            val metaWithTask = JsonObject(
              "io.modelcontextprotocol/related-task" -> Json.obj("taskId" -> request.taskId.asJson)
            )

            awaitTerminal
              .flatMap { task =>
                registry.getResult(request.taskId).map { resultOpt =>
                  resultOpt match {
                    case Some(result) =>
                      result.asObject match {
                        case Some(obj) => Right(obj.add("_meta", metaWithTask.asJson))
                        case None      => Right(JsonObject("result" -> result, "_meta" -> metaWithTask.asJson))
                      }
                    case None =>
                      Left(McpError.invalidParams(s"Task ${request.taskId} reached status '${task.status}' but no result available"))
                  }
                }
              }
              .recover { case _: NoSuchElementException =>
                Left(McpError.invalidParams(s"Task not found: ${request.taskId}"))
              }
          case None =>
            McpError.methodNotFound("Tasks not enabled").asError
        }
      case Left(error) =>
        McpError.invalidParams(s"Invalid request: ${error.getMessage}").asError
    }
  }

  // ============================================================================
  // NOTIFICATION API
  // ============================================================================

  def notifyResourceUpdated(uri: ResourceUri): F[Unit] =
    for {
      state <- connectionState.get
      transportOpt <- activeTransport.get
      _ <- (transportOpt, state.resourceSubscriptions.contains(uri)) match {
        case (Some(transport), true) =>
          val notification = JsonRpcResponse.Notification(
            jsonrpc = Constants.JSONRPC_VERSION,
            method = "notifications/resources/updated",
            params = Some(ResourceUpdatedNotification(uri.value).asJsonObject)
          )
          transport.send(notification)
        case _ =>
          Async[F].unit
      }
    } yield ()

  def notifyResourceListChanged(): F[Unit] =
    sendNotification(
      "notifications/resources/list_changed",
      ResourceListChangedNotification().asJsonObject
    )

  def notifyToolListChanged(): F[Unit] =
    sendNotification(
      "notifications/tools/list_changed",
      ToolListChangedNotification().asJsonObject
    )

  def notifyPromptListChanged(): F[Unit] =
    sendNotification(
      "notifications/prompts/list_changed",
      PromptListChangedNotification().asJsonObject
    )

  /** Send a notification to the connected client if a transport is active. */
  private def sendNotification(method: String, params: JsonObject): F[Unit] =
    activeTransport.get.flatMap {
      case Some(transport) =>
        val notification = JsonRpcResponse.Notification(
          jsonrpc = Constants.JSONRPC_VERSION,
          method = method,
          params = Some(params)
        )
        transport.send(notification)
      case None =>
        Async[F].unit
    }

  // ============================================================================
  // DYNAMIC ADD/REMOVE
  // ============================================================================

  def addTools(tools: List[ToolDef[F, _, _]]): F[Unit] =
    toolsRef.update(m => m ++ tools.map(t => t.name -> t)) *> notifyToolListChanged()

  def removeTools(names: List[String]): F[Unit] =
    toolsRef.update(m => m -- names) *> notifyToolListChanged()

  def addResources(resources: List[ResourceDef[F, _]]): F[Unit] =
    resourcesRef.update(m => m ++ resources.map(r => r.uri -> r)) *>
      startResourceUpdateStreamsFor(resources) *>
      notifyResourceListChanged()

  def removeResources(uris: List[String]): F[Unit] =
    resourcesRef.update(m => m -- uris) *> notifyResourceListChanged()

  def addResourceTemplates(templates: List[ResourceTemplateDef[F]]): F[Unit] =
    resourceTemplatesRef.update(_ ++ templates) *>
      startTemplateUpdateStreamsFor(templates) *>
      notifyResourceListChanged()

  def removeResourceTemplates(uriTemplates: List[String]): F[Unit] =
    resourceTemplatesRef.update(ts => ts.filterNot(t => uriTemplates.contains(t.uriTemplate))) *>
      notifyResourceListChanged()

  def addPrompts(prompts: List[PromptDef[F, _]]): F[Unit] =
    promptsRef.update(m => m ++ prompts.map(p => p.name -> p)) *> notifyPromptListChanged()

  def removePrompts(names: List[String]): F[Unit] =
    promptsRef.update(m => m -- names) *> notifyPromptListChanged()

  def addCompletions(completions: List[CompletionDef[F]]): F[Unit] =
    completionsRef.update(_ ++ completions)

  def removeCompletions(refs: List[CompletionReference]): F[Unit] =
    completionsRef.update(cs => cs.filterNot(c => refs.contains(c.ref)))

  /** Start update stream piping for newly added resources. */
  private def startResourceUpdateStreamsFor(resources: List[ResourceDef[F, _]]): F[Unit] = {
    val streams = resources.map(r => r.updates.as(ResourceUri(r.uri)))
    if streams.nonEmpty then
      Async[F].start(fs2.Stream.emits(streams).parJoinUnbounded.evalMap(resourceUpdateQueue.offer).compile.drain).void
    else Async[F].unit
  }

  /** Start update stream piping for newly added resource templates. */
  private def startTemplateUpdateStreamsFor(templates: List[ResourceTemplateDef[F]]): F[Unit] = {
    val streams = templates.map(_.updates)
    if streams.nonEmpty then
      Async[F].start(fs2.Stream.emits(streams).parJoinUnbounded.evalMap(resourceUpdateQueue.offer).compile.drain).void
    else Async[F].unit
  }
}

extension (ed: ErrorData) {
  def asError[F[_]: Async, A]: F[Either[ErrorData, A]] = Async[F].pure(Left(ed))
}
