package mcp.server

import cats.effect.{Async, Ref, Resource as CatsResource}
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.*

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
    * @param transport
    *   The transport to use for communication
    */
  def serve(transport: Transport[F]): F[Unit]

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
}
object McpServer {

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
    * @return
    *   Resource managing the server lifecycle
    */
  def apply[F[_]: Async](
      info: Implementation,
      tools: List[ToolDef[F, _, _]] = Nil,
      resources: List[ResourceDef[F, _]] = Nil,
      resourceTemplates: List[ResourceTemplateDef[F]] = Nil,
      prompts: List[PromptDef[F, _]] = Nil,
      completions: List[CompletionDef[F]] = Nil
  ): CatsResource[F, McpServer[F]] =
    CatsResource.eval {
      for {
        connectionState <- Ref.of[F, ConnectionState](ConnectionState.Uninitialized)
        activeTransport <- Ref.of[F, Option[Transport[F]]](None)
      } yield new McpServerImpl[F](
        serverInfo = info,
        toolsMap = tools.map(t => t.name -> t).toMap,
        resourcesMap = resources.map(r => r.uri -> r).toMap,
        resourceTemplates = resourceTemplates,
        promptsMap = prompts.map(p => p.name -> p).toMap,
        completionProviders = completions,
        connectionState = connectionState,
        activeTransport = activeTransport
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
    toolsMap: Map[String, ToolDef[F, _, _]],
    resourcesMap: Map[String, ResourceDef[F, _]],
    resourceTemplates: List[ResourceTemplateDef[F]],
    promptsMap: Map[String, PromptDef[F, _]],
    completionProviders: List[CompletionDef[F]],
    connectionState: Ref[F, ConnectionState],
    activeTransport: Ref[F, Option[Transport[F]]]
) extends McpServer[F] {

  /** Merged stream of all resource updates from resources and templates. */
  private val resourceUpdates: fs2.Stream[F, ResourceUri] = {
    // Static resources: map Unit emissions to their URI
    val staticUpdates = resourcesMap.values.toList.map { r =>
      r.updates.as(ResourceUri(r.uri))
    }
    // Template resources: already emit ResourceUri
    val templateUpdates = resourceTemplates.map(_.updates)

    // Merge all streams
    fs2.Stream.emits(staticUpdates ++ templateUpdates).parJoinUnbounded
  }

  def info: Implementation = serverInfo

  def capabilities: ServerCapabilities = {
    val hasResources = resourcesMap.nonEmpty || resourceTemplates.nonEmpty
    ServerCapabilities(
      tools = if toolsMap.nonEmpty then Some(ToolsCapability(listChanged = Some(true))) else None,
      resources = if hasResources then Some(ResourcesCapability(subscribe = Some(true), listChanged = Some(true))) else None,
      prompts = if promptsMap.nonEmpty then Some(PromptsCapability(listChanged = Some(true))) else None,
      completions = if completionProviders.nonEmpty then Some(JsonObject.empty) else None
    )
  }

  def serve(transport: Transport[F]): F[Unit] =
    Async[F].bracket(activeTransport.set(Some(transport))) { _ =>
      // Main message handling stream
      val messageStream = transport.receive
        .evalMap(handleMessage(_, transport))
        .collect { case Some(msg) => msg }
        .foreach(response => transport.send(response))

      // Resource updates stream - sends notifications for subscribed URIs
      val updatesStream = resourceUpdates.evalMap(notifyResourceUpdated)

      // Run both streams concurrently - updates stream runs alongside message handling
      messageStream.concurrently(updatesStream).compile.drain
    }(_ => activeTransport.set(None))

  /** Handle an incoming JSON-RPC request and optionally generate a response.
    *
    * Returns None for notifications (which should not receive responses per JSON-RPC spec).
    */
  private def handleMessage(message: JsonRpcRequest, transport: Transport[F]): F[Option[JsonRpcResponse]] =
    message match {
      case JsonRpcRequest.Request(jsonrpc, id, method, params) =>
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
                ErrorData(
                  code = Constants.INTERNAL_ERROR,
                  message = error.getMessage,
                  data = None
                )
              )
            )
          }

      case JsonRpcRequest.Notification(_, method, params) =>
        // Notifications don't get responses per JSON-RPC 2.0 spec
        handleNotification(method, transport).as(None)
    }

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
          ErrorData(code = Constants.INTERNAL_ERROR, message = "Server not initialized").asError
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
        withCapabilities(_ => handleListTools())

      case "tools/call" =>
        withCapabilities(caps => handleCallTool(params, transport, caps))

      case "resources/list" =>
        withCapabilities(_ => handleListResources())

      case "resources/templates/list" =>
        withCapabilities(_ => handleListResourceTemplates())

      case "resources/read" =>
        withCapabilities(caps => handleReadResource(params, transport, caps))

      case "resources/subscribe" =>
        withCapabilities(_ => handleSubscribe(params, transport))

      case "resources/unsubscribe" =>
        withCapabilities(_ => handleUnsubscribe(params))

      case "prompts/list" =>
        withCapabilities(_ => handleListPrompts())

      case "prompts/get" =>
        withCapabilities(caps => handleGetPrompt(params, caps))

      case "logging/setLevel" =>
        withCapabilities(_ => handleSetLevel(params))

      case "completion/complete" =>
        withCapabilities(_ => handleComplete(params))

      case _ =>
        ErrorData(code = Constants.METHOD_NOT_FOUND, message = s"Method not found: $method").asError
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
  private def handleNotification(method: String, transport: Transport[F]): F[Unit] =
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
        // TODO: Handle cancellation
        Async[F].unit

      case "notifications/roots/list_changed" =>
        // Client notified us that roots changed - fetch fresh roots
        fetchRoots(transport)

      case _ =>
        // Unknown notification, ignore
        Async[F].unit
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
                serverInfo = info
              ).asJsonObject
            )
          )

      case Left(error) =>
        ErrorData(code = Constants.INVALID_PARAMS, message = s"Invalid initialize request: ${error.getMessage}").asError
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
            ErrorData(code = Constants.INTERNAL_ERROR, message = "Cannot set log level before initialization").asError
        }

      case Left(error) =>
        ErrorData(code = Constants.INVALID_PARAMS, message = s"Invalid logging/setLevel request: ${error.getMessage}").asError
    }
  }

  private def handleListTools(): F[Either[ErrorData, JsonObject]] = {
    val result = ListToolsResult(tools = toolsMap.values.map(_.toTool).toList)
    Async[F].pure(Right(result.asJsonObject))
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

    // Get minimum log level and roots from connection state
    connectionState.get.flatMap { state =>
      val context = ToolContextImpl[F](transport, progressToken, state.minLogLevel, state.rootsList)

      paramsJson.as[CallToolRequest] match {
        case Right(request) =>
          toolsMap.get(request.name) match {
            case Some(toolDef) =>
              toolDef.execute(request.arguments, Some(context)).map(result => Right(result.asJsonObject))
            case None =>
              ErrorData(code = Constants.INVALID_PARAMS, message = s"Tool not found: ${request.name}").asError
          }

        case Left(error) =>
          ErrorData(code = Constants.INVALID_PARAMS, message = s"Invalid tool call request: ${error.getMessage}").asError
      }
    }
  }

  private def handleListResources(): F[Either[ErrorData, JsonObject]] = {
    val result = ListResourcesResult(resources = resourcesMap.values.map(_.toResource).toList)
    Async[F].pure(Right(result.asJsonObject))
  }

  private def handleListResourceTemplates(): F[Either[ErrorData, JsonObject]] = {
    val result = ListResourceTemplatesResult(resourceTemplates = resourceTemplates.map(_.toResourceTemplate))
    Async[F].pure(Right(result.asJsonObject))
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
        resourcesMap.get(request.uri) match {
          case Some(resourceDef) =>
            // Get minimum log level and roots from connection state
            connectionState.get.flatMap { state =>
              val context = ResourceContextImpl[F](transport, state.minLogLevel, state.rootsList)
              resourceDef.read(context).map(result => Right(result.asJsonObject))
            }
          case None =>
            // No static match - try templates
            resolveFromTemplates(request.uri, transport)
        }

      case Left(error) =>
        ErrorData(code = Constants.INVALID_PARAMS, message = s"Invalid read resource request: ${error.getMessage}").asError
    }
  }

  /** Try to resolve a URI using resource templates.
    *
    * Templates are checked in order; the first matching template that resolves to a ResourceDef is used.
    */
  private def resolveFromTemplates(uri: String, transport: Transport[F]): F[Either[ErrorData, JsonObject]] =
    connectionState.get.flatMap { state =>
      val context = ResourceContextImpl[F](transport, state.minLogLevel, state.rootsList)

      // Find first matching template and try to resolve
      def tryTemplates(remaining: List[ResourceTemplateDef[F]]): F[Either[ErrorData, JsonObject]] =
        remaining match {
          case Nil =>
            ErrorData(code = Constants.INVALID_PARAMS, message = s"Resource not found: $uri").asError
          case template :: rest =>
            if template.matches(uri) then {
              template.resolve(uri, context).flatMap {
                case Some(resourceDef) =>
                  resourceDef.read(context).map(result => Right(result.asJsonObject))
                case None =>
                  // Template matched but resolver returned None, try next template
                  tryTemplates(rest)
              }
            } else {
              tryTemplates(rest)
            }
        }

      tryTemplates(resourceTemplates)
    }

  private def validateResourceExists(uri: ResourceUri, transport: Transport[F]): F[Boolean] =
    // First check static resources
    if resourcesMap.contains(uri.value) then {
      Async[F].pure(true)
    } else {
      // Try templates with full resolution
      connectionState.get.flatMap { state =>
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

        tryTemplates(resourceTemplates)
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
            ErrorData(
              code = Constants.INVALID_PARAMS,
              message = s"Resource not found: ${request.uri}"
            ).asError
        }

      case Left(error) =>
        ErrorData(
          code = Constants.INVALID_PARAMS,
          message = s"Invalid subscribe request: ${error.getMessage}"
        ).asError
    }
  }

  private def handleUnsubscribe(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[UnsubscribeRequest] match {
      case Right(request) =>
        removeSubscription(ResourceUri(request.uri)).as(Right(EmptyResult().asJsonObject))

      case Left(error) =>
        ErrorData(
          code = Constants.INVALID_PARAMS,
          message = s"Invalid unsubscribe request: ${error.getMessage}"
        ).asError
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

  private def handleListPrompts(): F[Either[ErrorData, JsonObject]] = {
    val result = ListPromptsResult(prompts = promptsMap.values.map(_.toPrompt).toList)
    Async[F].pure(Right(result.asJsonObject))
  }

  private def handleGetPrompt(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[GetPromptRequest] match {
      case Right(request) =>
        promptsMap.get(request.name) match {
          case Some(promptDef) =>
            promptDef.get(request.arguments).map(result => Right(result.asJsonObject))
          case None =>
            ErrorData(code = Constants.INVALID_PARAMS, message = s"Prompt not found: ${request.name}").asError
        }

      case Left(error) =>
        ErrorData(code = Constants.INVALID_PARAMS, message = s"Invalid get prompt request: ${error.getMessage}").asError
    }
  }

  private def handleComplete(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[CompleteRequest] match {
      case Right(request) =>
        findCompletionProvider(request.ref) match {
          case Some(provider) =>
            provider.complete(request.argument).map(result => Right(result.asJsonObject))
          case None =>
            // No provider found - return empty completions (not an error per MCP spec)
            Async[F].pure(Right(CompleteResult(completion = CompletionCompletion(values = Nil)).asJsonObject))
        }

      case Left(error) =>
        ErrorData(code = Constants.INVALID_PARAMS, message = s"Invalid completion request: ${error.getMessage}").asError
    }
  }

  /** Find a completion provider matching the given reference.
    *
    * Matches by:
    *   - For prompts: exact name match
    *   - For resource templates: exact URI template match
    */
  private def findCompletionProvider(ref: CompletionReference): Option[CompletionDef[F]] =
    completionProviders.find { provider =>
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
}

extension (ed: ErrorData) {
  def asError[F[_]: Async, A]: F[Either[ErrorData, A]] = Async[F].pure(Left(ed))
}
