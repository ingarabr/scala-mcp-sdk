package mcp.server

import cats.ApplicativeError
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{Resource as ProtocolResource, *}
import mcp.schema.McpSchema

/** A tool definition with typed input and optional typed output.
  *
  * Create instances using:
  *   - `ToolDef.unstructured` for content tools (text, images, etc.)
  *   - `ToolDef.structured` for typed data tools
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @tparam Input
  *   Input type (must have McpSchema instance)
  * @tparam Output
  *   Output type (must have McpSchema if structured, or Nothing if unstructured)
  */
final case class ToolDef[F[_], Input, Output] private (
    name: String,
    description: Option[String],
    handler: (Input, ToolContext[F]) => F[ToolOutput[Output]],
    annotations: Option[ToolAnnotations] = None
)(using
    val inputSchema: McpSchema[Input],
    val outputSchema: Option[McpSchema[Output]]
) {

  /** Convert to protocol Tool type for listing */
  def toTool: Tool =
    Tool(
      name = name,
      description = description,
      inputSchema = inputSchema.jsonSchema,
      outputSchema = outputSchema.map(_.jsonSchema),
      annotations = annotations
    )

  /** Execute the tool with the given arguments, handling encoding/decoding internally */
  def execute(arguments: Option[JsonObject], context: Option[ToolContext[F]] = None)(using
      F: ApplicativeError[F, Throwable],
      S: cats.effect.kernel.Sync[F]
  ): F[CallToolResult] = {
    val argsJson = arguments.getOrElse(JsonObject.empty).asJson

    // Decode input using the schema's codec
    val inputResult = argsJson.as[Input](inputSchema.decoder)

    inputResult match {
      case Right(input) =>
        val ctx = context.getOrElse(ToolContextImpl.noop[F])

        handler(input, ctx)
          .map(encodeToolOutput)
          .handleError { error =>
            CallToolResult(
              content = List(Content.Text(s"Tool execution failed: ${error.getMessage}")),
              isError = Some(true)
            )
          }

      case Left(error) =>
        F.pure(
          CallToolResult(
            content = List(Content.Text(s"Invalid input: ${error.getMessage}")),
            isError = Some(true)
          )
        )
    }
  }

  /** Encode ToolOutput into CallToolResult without type casting */
  private def encodeToolOutput(output: ToolOutput[Output]): CallToolResult =
    output match {
      case ToolOutput.Unstructured(content) =>
        // Return content directly for unstructured output
        CallToolResult(
          content = content,
          isError = Some(false)
        )
      case ToolOutput.Structured(data) =>
        outputSchema match {
          case Some(schema) =>
            val jsonContent = schema.encoder(data).dropNullValues
            CallToolResult(
              content = List(Content.Text(jsonContent.noSpaces)),
              structuredContent = jsonContent.asObject,
              isError = Some(false)
            )
          case None =>
            // This shouldn't happen due to types, but handle gracefully
            CallToolResult(
              content = List(Content.Text(s"Unable to encode result: $data")),
              isError = Some(true)
            )
        }
    }
}

object ToolDef {

  /** Create a tool that returns flexible content (text, images, multiple items).
    *
    * Use this for human-readable content. Ignore the context parameter if you don't need progress/logging.
    *
    * Example:
    * {{{
    * ToolDef.unstructured[IO, SearchInput](
    *   name = "search"
    * ) { (input, _) =>
    *   IO.pure(List(Content.Text("Results...")))
    * }
    * }}}
    */
  def unstructured[F[_], Input](
      name: String,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None
  )(handler: (Input, ToolContext[F]) => F[List[Content]])(using
      schema: McpSchema[Input],
      F: cats.Functor[F]
  ): ToolDef[F, Input, Nothing] =
    ToolDef[F, Input, Nothing](
      name = name,
      description = description,
      handler = (input, ctx) => F.map(handler(input, ctx))(ToolOutput.Unstructured(_)),
      annotations = annotations
    )(using schema, None)

  /** Create a tool that returns structured, typed data.
    *
    * Use this for typed, machine-readable data. Ignore the context parameter if you don't need progress/logging.
    *
    * Example:
    * {{{
    * ToolDef.structured[IO, AddInput, AddOutput](
    *   name = "add"
    * ) { (input, _) =>
    *   IO.pure(AddOutput(input.a + input.b))
    * }
    * }}}
    */
  def structured[F[_], Input, Output](
      name: String,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None
  )(handler: (Input, ToolContext[F]) => F[Output])(using
      inputSchema: McpSchema[Input],
      outputSchema: McpSchema[Output],
      F: cats.Functor[F]
  ): ToolDef[F, Input, Output] =
    ToolDef[F, Input, Output](
      name = name,
      description = description,
      handler = (input, ctx) => F.map(handler(input, ctx))(ToolOutput.Structured(_)),
      annotations = annotations
    )(using inputSchema, Some(outputSchema))
}

/** A resource definition with typed output.
  *
  * This is an applicative data structure for resources:
  *   - Specification (URI, name, description, MIME type)
  *   - Type-safe handler (=> F[Output])
  *
  * The handler returns typed data, which is automatically serialized based on MIME type.
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @tparam Output
  *   Output type (must have Encoder)
  * @param uri
  *   Unique URI for the resource
  * @param name
  *   Human-readable name
  * @param description
  *   Optional description
  * @param mimeType
  *   Optional MIME type (defaults to "text/plain")
  * @param handler
  *   Function to read the resource (by-name parameter for lazy evaluation)
  * @param outputEncoder
  *   Encoder for serializing output
  */
case class ResourceDef[F[_], Output](
    uri: String,
    name: String,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    handler: () => F[Output]
)(using val outputEncoder: Encoder[Output]) {

  /** Convert to protocol Resource type for listing */
  def toResource: ProtocolResource =
    ProtocolResource(
      uri = uri,
      name = name,
      description = description,
      mimeType = mimeType
    )

  /** Read the resource contents, handling encoding internally */
  def read(using F: ApplicativeError[F, Throwable]): F[ReadResourceResult] =
    handler()
      .map { output =>
        val text = outputEncoder(output).spaces2
        ReadResourceResult(
          contents = List(
            ResourceContents.Text(
              uri = uri,
              text = text,
              mimeType = mimeType.orElse(Some("application/json"))
            )
          )
        )
      }
      .handleErrorWith { error =>
        F.pure(
          ReadResourceResult(
            contents = List(
              ResourceContents.Text(
                uri = uri,
                text = s"Failed to read resource: ${error.getMessage}",
                mimeType = Some("text/plain")
              )
            )
          )
        )
      }
}

/** A prompt definition with typed arguments.
  *
  * This is an applicative data structure for prompts:
  *   - Specification (name, description, argument list)
  *   - Type-safe handler (Args => F[List[PromptMessage]])
  *
  * The handler receives typed arguments and returns prompt messages.
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @tparam Args
  *   Argument type (must have Decoder)
  * @param name
  *   Unique name for the prompt
  * @param description
  *   Optional description
  * @param arguments
  *   List of argument specifications
  * @param handler
  *   Function to generate prompt messages from typed arguments
  * @param argsDecoder
  *   Decoder for parsing argument JSON
  */
case class PromptDef[F[_], Args](
    name: String,
    description: Option[String],
    arguments: List[PromptArgument],
    handler: Args => F[List[PromptMessage]]
)(using val argsDecoder: Decoder[Args]) {

  /** Convert to protocol Prompt type for listing */
  def toPrompt: Prompt =
    Prompt(
      name = name,
      description = description,
      arguments = if arguments.isEmpty then None else Some(arguments)
    )

  /** Get the prompt with the given arguments, handling decoding internally */
  def get(args: Option[JsonObject])(using F: ApplicativeError[F, Throwable]): F[GetPromptResult] = {
    val argsJson = args.getOrElse(JsonObject.empty).asJson
    val argsResult = argsJson.as[Args]

    argsResult match {
      case Right(typedArgs) =>
        handler(typedArgs)
          .map { messages =>
            GetPromptResult(
              description = description,
              messages = messages
            )
          }
          .handleError { error =>
            GetPromptResult(
              description = Some(s"Failed to generate prompt: ${error.getMessage}"),
              messages = List(
                PromptMessage(
                  role = Role.assistant,
                  content = Content.Text(s"Error: ${error.getMessage}")
                )
              )
            )
          }

      case Left(error) =>
        F.pure(
          GetPromptResult(
            description = Some(s"Invalid arguments: ${error.getMessage}"),
            messages = List(
              PromptMessage(
                role = Role.assistant,
                content = Content.Text(s"Error: ${error.getMessage}")
              )
            )
          )
        )
    }
  }
}
