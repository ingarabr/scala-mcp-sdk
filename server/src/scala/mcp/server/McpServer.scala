package mcp.server

import cats.effect.{Async, Ref, Resource as CatsResource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
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
      Ref.of[F, Boolean](false).map { initializedRef =>
        new McpServerImpl[F](
          serverInfo = info,
          toolsMap = tools.map(t => t.name -> t).toMap,
          resourcesMap = resources.map(r => r.uri -> r).toMap,
          promptsMap = prompts.map(p => p.name -> p).toMap,
          initializedRef = initializedRef
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
    initializedRef: Ref[F, Boolean]
) extends McpServer[F] {

  def info: Implementation = serverInfo

  def capabilities: ServerCapabilities = {
    ServerCapabilities(
      tools = if toolsMap.nonEmpty then Some(ToolsCapability(listChanged = Some(false))) else None,
      resources = if resourcesMap.nonEmpty then Some(ResourcesCapability(subscribe = Some(false), listChanged = Some(false))) else None,
      prompts = if promptsMap.nonEmpty then Some(PromptsCapability(listChanged = Some(false))) else None
    )
  }

  def serve(transport: Transport[F]): F[Unit] = {
    transport.receive
      .evalMap(handleMessage)
      .collect { case Some(msg) => msg }
      .foreach(response => transport.send(response))
      .compile
      .drain
  }

  /** Handle an incoming JSON-RPC message and optionally generate a response.
    *
    * Returns None for notifications (which should not receive responses per JSON-RPC spec).
    */
  private def handleMessage(message: JsonRpcMessage): F[Option[JsonRpcMessage]] = {
    message match {
      case JsonRpcMessage.Request(jsonrpc, id, method, params) =>
        handleRequest(method, params)
          .map { result =>
            Some(JsonRpcMessage.Response(jsonrpc, id, result))
          }
          .handleErrorWith { error =>
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
  }

  /** Handle a request and return the result */
  private def handleRequest(method: String, params: Option[JsonObject]): F[JsonObject] = {
    method match {
      case "initialize" =>
        handleInitialize(params)

      case "tools/list" =>
        handleListTools(params)

      case "tools/call" =>
        handleCallTool(params)

      case "resources/list" =>
        handleListResources(params)

      case "resources/read" =>
        handleReadResource(params)

      case "prompts/list" =>
        handleListPrompts(params)

      case "prompts/get" =>
        handleGetPrompt(params)

      case _ =>
        Async[F].raiseError(new Exception(s"Method not found: $method"))
    }
  }

  /** Handle notifications (fire and forget) */
  private def handleNotification(method: String, params: Option[JsonObject]): F[Unit] = {
    method match {
      case "initialized" =>
        initializedRef.set(true)

      case "notifications/cancelled" =>
        // TODO: Handle cancellation
        Async[F].unit

      case _ =>
        // Unknown notification, ignore
        Async[F].unit
    }
  }

  // Request handlers

  private def handleInitialize(params: Option[JsonObject]): F[JsonObject] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[InitializeRequest] match {
      case Right(request) =>
        val result = InitializeResult(
          protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
          capabilities = capabilities,
          serverInfo = info
        )
        Async[F].pure(result.asJsonObject)

      case Left(error) =>
        Async[F].raiseError(new Exception(s"Invalid initialize request: ${error.getMessage}"))
    }
  }

  private def handleListTools(params: Option[JsonObject]): F[JsonObject] = {
    val result = ListToolsResult(tools = toolsMap.values.map(_.toTool).toList)
    Async[F].pure(result.asJsonObject)
  }

  private def handleCallTool(params: Option[JsonObject]): F[JsonObject] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[CallToolRequest] match {
      case Right(request) =>
        toolsMap.get(request.name) match {
          case Some(toolDef) =>
            toolDef.execute(request.arguments).map(_.asJsonObject)
          case None =>
            Async[F].raiseError(new Exception(s"Tool not found: ${request.name}"))
        }

      case Left(error) =>
        Async[F].raiseError(new Exception(s"Invalid tool call request: ${error.getMessage}"))
    }
  }

  private def handleListResources(params: Option[JsonObject]): F[JsonObject] = {
    val result = ListResourcesResult(resources = resourcesMap.values.map(_.toResource).toList)
    Async[F].pure(result.asJsonObject)
  }

  private def handleReadResource(params: Option[JsonObject]): F[JsonObject] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[ReadResourceRequest] match {
      case Right(request) =>
        resourcesMap.get(request.uri) match {
          case Some(resourceDef) =>
            resourceDef.read.map(_.asJsonObject)
          case None =>
            Async[F].raiseError(new Exception(s"Resource not found: ${request.uri}"))
        }

      case Left(error) =>
        Async[F].raiseError(new Exception(s"Invalid read resource request: ${error.getMessage}"))
    }
  }

  private def handleListPrompts(params: Option[JsonObject]): F[JsonObject] = {
    val result = ListPromptsResult(prompts = promptsMap.values.map(_.toPrompt).toList)
    Async[F].pure(result.asJsonObject)
  }

  private def handleGetPrompt(params: Option[JsonObject]): F[JsonObject] = {
    val paramsJson = params.getOrElse(JsonObject.empty).asJson
    paramsJson.as[GetPromptRequest] match {
      case Right(request) =>
        promptsMap.get(request.name) match {
          case Some(promptDef) =>
            promptDef.get(request.arguments).map(_.asJsonObject)
          case None =>
            Async[F].raiseError(new Exception(s"Prompt not found: ${request.name}"))
        }

      case Left(error) =>
        Async[F].raiseError(new Exception(s"Invalid get prompt request: ${error.getMessage}"))
    }
  }
}
