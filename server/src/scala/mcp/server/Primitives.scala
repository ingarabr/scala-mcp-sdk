package mcp.server

import cats.ApplicativeError
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{Resource as ProtocolResource, *}
import mcp.schema.McpSchema

/** A tool definition with typed input and output.
  *
  * This is an applicative data structure that contains everything needed for a tool:
  *   - Specification (name, description, schema)
  *   - Type-safe handler (Input => F[Output])
  *   - Annotations
  *
  * The handler works with typed data structures, and JSON encoding/decoding happens automatically. The input schema is derived from the
  * Input type using the Schema type class, which extracts field descriptions from Scaladoc comments.
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @tparam Input
  *   Input type (must have Schema instance)
  * @tparam Output
  *   Output type (must have Encoder)
  * @param name
  *   Unique name for the tool
  * @param description
  *   Human-readable description
  * @param handler
  *   Type-safe function to execute the tool
  * @param annotations
  *   Optional annotations about tool behavior
  * @param inputSchema
  *   Schema for input type (provides both codec and JSON schema)
  * @param outputEncoder
  *   Encoder for serializing output
  */
case class ToolDef[F[_], Input, Output](
    name: String,
    description: Option[String],
    handler: Input => F[Output],
    annotations: Option[ToolAnnotations] = None
)(using
    val inputSchema: McpSchema[Input],
    val outputEncoder: Encoder[Output]
) {

  /** Convert to protocol Tool type for listing */
  def toTool: Tool =
    Tool(
      name = name,
      description = description,
      inputSchema = inputSchema.jsonSchema,
      annotations = annotations
    )

  /** Execute the tool with the given arguments, handling encoding/decoding internally */
  def execute(arguments: Option[JsonObject])(using F: ApplicativeError[F, Throwable]): F[CallToolResult] = {
    val argsJson = arguments.getOrElse(JsonObject.empty).asJson

    // Decode input using the schema's codec
    val inputResult = argsJson.as[Input](inputSchema.decoder)

    inputResult match {
      case Right(input) =>
        // Execute handler and encode output
        handler(input)
          .map { output =>
            CallToolResult(
              content = List(Content.Text(outputEncoder(output).dropNullValues.noSpaces)),
              isError = Some(false)
            )
          }
          .handleErrorWith { error =>
            F.pure(
              CallToolResult(
                content = List(Content.Text(s"Tool execution failed: ${error.getMessage}")),
                isError = Some(true)
              )
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
          .handleErrorWith { error =>
            F.pure(
              GetPromptResult(
                description = Some(s"Failed to generate prompt: ${error.getMessage}"),
                messages = List(
                  PromptMessage(
                    role = Role.assistant,
                    content = Content.Text(s"Error: ${error.getMessage}")
                  )
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
