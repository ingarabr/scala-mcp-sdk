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
    * @return
    *   Resource managing the server lifecycle
    */
  def apply[F[_]: Async](
      info: Implementation,
      tools: List[ToolDef[F, _, _]] = Nil,
      resources: List[ResourceDef[F, _]] = Nil,
      resourceTemplates: List[ResourceTemplateDef[F]] = Nil,
      prompts: List[PromptDef[F, _]] = Nil
  ): CatsResource[F, McpServer[F]] =
    CatsResource.eval {
      Ref.of[F, ConnectionState](ConnectionState.Uninitialized).map { connectionState =>
        new McpServerImpl[F](
          serverInfo = info,
          toolsMap = tools.map(t => t.name -> t).toMap,
          resourcesMap = resources.map(r => r.uri -> r).toMap,
          resourceTemplates = resourceTemplates,
          promptsMap = prompts.map(p => p.name -> p).toMap,
          connectionState = connectionState
        )
      }
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
    connectionState: Ref[F, ConnectionState]
) extends McpServer[F] {

  def info: Implementation = serverInfo

  def capabilities: ServerCapabilities = {
    val hasResources = resourcesMap.nonEmpty || resourceTemplates.nonEmpty
    ServerCapabilities(
      tools = if toolsMap.nonEmpty then Some(ToolsCapability(listChanged = Some(false))) else None,
      resources = if hasResources then Some(ResourcesCapability(subscribe = Some(false), listChanged = Some(false))) else None,
      prompts = if promptsMap.nonEmpty then Some(PromptsCapability(listChanged = Some(false))) else None
    )
  }

  def serve(transport: Transport[F]): F[Unit] =
    transport.receive
      .evalMap(handleMessage(_, transport))
      .collect { case Some(msg) => msg }
      .foreach(response => transport.send(response))
      .compile
      .drain

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

      case "prompts/list" =>
        withCapabilities(_ => handleListPrompts())

      case "prompts/get" =>
        withCapabilities(caps => handleGetPrompt(params, caps))

      case "logging/setLevel" =>
        withCapabilities(_ => handleSetLevel(params))

      case _ =>
        ErrorData(code = Constants.METHOD_NOT_FOUND, message = s"Method not found: $method").asError
    }

  /** Handle notifications (fire and forget) */
  private def handleNotification(method: String, transport: Transport[F]): F[Unit] =
    method match {
      case "notifications/initialized" =>
        // Transition from Initialized to Operational state, preserving minLogLevel and roots
        connectionState.get.flatMap {
          case ConnectionState.Initialized(caps, minLogLevel, roots) =>
            connectionState.set(ConnectionState.Operational(caps, minLogLevel, roots))
          case _ =>
            // Already operational or not yet initialized - ignore
            Async[F].unit
        }

      case "notifications/cancelled" =>
        // TODO: Handle cancellation
        Async[F].unit

      case "notifications/roots/list_changed" =>
        // Client notified us that roots changed - proactively fetch and cache new roots
        connectionState.get.flatMap {
          case ConnectionState.Initialized(caps, logLevel, _) =>
            caps.roots match {
              case Some(_) =>
                // Client supports roots - fetch fresh data
                transport.sendRequest("roots/list", None).flatMap {
                  case Right(resultObj) =>
                    // Parse and cache the new roots
                    resultObj.asJson.as[ListRootsResult] match {
                      case Right(result) =>
                        connectionState.set(ConnectionState.Initialized(caps, logLevel, Some(result.roots)))
                      case Left(_) =>
                        // Failed to parse - clear cache
                        connectionState.set(ConnectionState.Initialized(caps, logLevel, None))
                    }
                  case Left(_) =>
                    // Failed to fetch - clear cache
                    connectionState.set(ConnectionState.Initialized(caps, logLevel, None))
                }
              case None =>
                Async[F].unit // Client doesn't support roots - ignore
            }
          case ConnectionState.Operational(caps, logLevel, _) =>
            caps.roots match {
              case Some(_) =>
                // Client supports roots - fetch fresh data
                transport.sendRequest("roots/list", None).flatMap {
                  case Right(resultObj) =>
                    // Parse and cache the new roots
                    resultObj.asJson.as[ListRootsResult] match {
                      case Right(result) =>
                        connectionState.set(ConnectionState.Operational(caps, logLevel, Some(result.roots)))
                      case Left(_) =>
                        // Failed to parse - clear cache
                        connectionState.set(ConnectionState.Operational(caps, logLevel, None))
                    }
                  case Left(_) =>
                    // Failed to fetch - clear cache
                    connectionState.set(ConnectionState.Operational(caps, logLevel, None))
                }
              case None =>
                Async[F].unit // Client doesn't support roots - ignore
            }
          case _ =>
            // Not initialized - ignore
            Async[F].unit
        }

      case notif =>
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
          case ConnectionState.Initialized(caps, _, roots) =>
            connectionState.set(ConnectionState.Initialized(caps, Some(request.level), roots)).as(Right(EmptyResult().asJsonObject))
          case ConnectionState.Operational(caps, _, roots) =>
            connectionState.set(ConnectionState.Operational(caps, Some(request.level), roots)).as(Right(EmptyResult().asJsonObject))

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
}

extension (ed: ErrorData) {
  def asError[F[_]: Async, A]: F[Either[ErrorData, A]] = Async[F].pure(Left(ed))
}
