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
    * @param prompts
    *   List of prompt definitions (existential types)
    * @return
    *   Resource managing the server lifecycle
    */
  def apply[F[_]: Async](
      info: Implementation,
      tools: List[ToolDef[F, _, _]] = Nil,
      resources: List[ResourceDef[F, _]] = Nil,
      prompts: List[PromptDef[F, _]] = Nil
  ): CatsResource[F, McpServer[F]] =
    CatsResource.eval {
      Ref.of[F, ConnectionState](ConnectionState.Uninitialized).map { connectionState =>
        new McpServerImpl[F](
          serverInfo = info,
          toolsMap = tools.map(t => t.name -> t).toMap,
          resourcesMap = resources.map(r => r.uri -> r).toMap,
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
    promptsMap: Map[String, PromptDef[F, _]],
    connectionState: Ref[F, ConnectionState]
) extends McpServer[F] {

  def info: Implementation = serverInfo

  def capabilities: ServerCapabilities =
    ServerCapabilities(
      tools = if toolsMap.nonEmpty then Some(ToolsCapability(listChanged = Some(false))) else None,
      resources = if resourcesMap.nonEmpty then Some(ResourcesCapability(subscribe = Some(false), listChanged = Some(false))) else None,
      prompts = if promptsMap.nonEmpty then Some(PromptsCapability(listChanged = Some(false))) else None
    )

  def serve(transport: Transport[F]): F[Unit] =
    transport.receive
      .evalMap(handleMessage)
      .collect { case Some(msg) => msg }
      .foreach(response => transport.send(response))
      .compile
      .drain

  /** Handle an incoming JSON-RPC message and optionally generate a response.
    *
    * Returns None for notifications (which should not receive responses per JSON-RPC spec).
    */
  private def handleMessage(message: JsonRpcMessage): F[Option[JsonRpcMessage]] =
    message match {
      case JsonRpcMessage.Request(jsonrpc, id, method, params) =>
        handleRequest(method, params)
          .map {
            case Right(result) =>
              Some(JsonRpcMessage.Response(jsonrpc, id, result))
            case Left(errorData) =>
              Some(JsonRpcMessage.Error(jsonrpc, id, errorData))
          }
          .handleErrorWith { error =>
            // Catch-all for unexpected exceptions
            Async[F].pure(
              Some(
                JsonRpcMessage.Error(
                  jsonrpc,
                  id,
                  ErrorData(
                    code = Constants.INTERNAL_ERROR,
                    message = error.getMessage,
                    data = None
                  )
                )
              )
            )
          }

      case JsonRpcMessage.Notification(_, method, params) =>
        // Notifications don't get responses per JSON-RPC 2.0 spec
        handleNotification(method, params).as(None)

      case _ =>
        // Other message types (Response, Error, Batch) are not expected from clients
        Async[F].pure(
          Some(
            JsonRpcMessage.Error(
              Constants.JSONRPC_VERSION,
              RequestId("unknown"),
              ErrorData(
                code = Constants.INVALID_REQUEST,
                message = "Unexpected message type from client"
              )
            )
          )
        )
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
          Async[F].pure(
            Left(
              ErrorData(
                code = Constants.INTERNAL_ERROR,
                message = "Server not initialized"
              )
            )
          )
      }
    }

  /** Handle a request and return either an error or the result */
  private def handleRequest(method: String, params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] =
    method match {
      case "initialize" =>
        handleInitialize(params)

      case "tools/list" =>
        withCapabilities(caps => handleListTools(params, caps))

      case "tools/call" =>
        withCapabilities(caps => handleCallTool(params, caps))

      case "resources/list" =>
        withCapabilities(caps => handleListResources(params, caps))

      case "resources/read" =>
        withCapabilities(caps => handleReadResource(params, caps))

      case "prompts/list" =>
        withCapabilities(caps => handleListPrompts(params, caps))

      case "prompts/get" =>
        withCapabilities(caps => handleGetPrompt(params, caps))

      case _ =>
        Async[F].pure(
          Left(
            ErrorData(
              code = Constants.METHOD_NOT_FOUND,
              message = s"Method not found: $method"
            )
          )
        )
    }

  /** Handle notifications (fire and forget) */
  private def handleNotification(method: String, params: Option[JsonObject]): F[Unit] =
    method match {
      case "initialized" =>
        // Transition from Initialized to Operational state
        connectionState.get.flatMap {
          case ConnectionState.Initialized(caps) =>
            connectionState.set(ConnectionState.Operational(caps))
          case _ =>
            // Already operational or not yet initialized - ignore
            Async[F].unit
        }

      case "notifications/cancelled" =>
        // TODO: Handle cancellation
        Async[F].unit

      case _ =>
        // Unknown notification, ignore
        Async[F].unit
    }

  // Request handlers

  private def handleInitialize(params: Option[JsonObject]): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[InitializeRequest] match {
      case Right(request) =>
        for {
          _ <- connectionState.set(ConnectionState.Initialized(request.capabilities))

          result = InitializeResult(
            protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = info
          )
        } yield Right(result.asJsonObject)

      case Left(error) =>
        Async[F].pure(
          Left(
            ErrorData(
              code = Constants.INVALID_PARAMS,
              message = s"Invalid initialize request: ${error.getMessage}"
            )
          )
        )
    }
  }

  private def handleListTools(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
    val result = ListToolsResult(tools = toolsMap.values.map(_.toTool).toList)
    Async[F].pure(Right(result.asJsonObject))
  }

  private def handleCallTool(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[CallToolRequest] match {
      case Right(request) =>
        toolsMap.get(request.name) match {
          case Some(toolDef) =>
            toolDef.execute(request.arguments).map(result => Right(result.asJsonObject))
          case None =>
            Async[F].pure(
              Left(
                ErrorData(
                  code = Constants.INVALID_PARAMS,
                  message = s"Tool not found: ${request.name}"
                )
              )
            )
        }

      case Left(error) =>
        Async[F].pure(
          Left(
            ErrorData(
              code = Constants.INVALID_PARAMS,
              message = s"Invalid tool call request: ${error.getMessage}"
            )
          )
        )
    }
  }

  private def handleListResources(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
    val result = ListResourcesResult(resources = resourcesMap.values.map(_.toResource).toList)
    Async[F].pure(Right(result.asJsonObject))
  }

  private def handleReadResource(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[ReadResourceRequest] match {
      case Right(request) =>
        resourcesMap.get(request.uri) match {
          case Some(resourceDef) =>
            resourceDef.read.map(result => Right(result.asJsonObject))
          case None =>
            Async[F].pure(
              Left(
                ErrorData(
                  code = Constants.INVALID_PARAMS,
                  message = s"Resource not found: ${request.uri}"
                )
              )
            )
        }

      case Left(error) =>
        Async[F].pure(
          Left(
            ErrorData(
              code = Constants.INVALID_PARAMS,
              message = s"Invalid read resource request: ${error.getMessage}"
            )
          )
        )
    }
  }

  private def handleListPrompts(params: Option[JsonObject], capabilities: ClientCapabilities): F[Either[ErrorData, JsonObject]] = {
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
            Async[F].pure(
              Left(
                ErrorData(
                  code = Constants.INVALID_PARAMS,
                  message = s"Prompt not found: ${request.name}"
                )
              )
            )
        }

      case Left(error) =>
        Async[F].pure(
          Left(
            ErrorData(
              code = Constants.INVALID_PARAMS,
              message = s"Invalid get prompt request: ${error.getMessage}"
            )
          )
        )
    }
  }
}
