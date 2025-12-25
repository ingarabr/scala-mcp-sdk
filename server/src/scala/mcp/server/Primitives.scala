package mcp.server

import cats.ApplicativeError
import cats.effect.Async
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{JsonSchemaType, Resource as ProtocolResource, *}
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
    annotations: Option[ToolAnnotations] = None,
    icons: Option[List[Icon]] = None
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
      annotations = annotations,
      icons = icons
    )

  /** Execute the tool with the given arguments, handling encoding/decoding internally */
  def execute(arguments: Option[JsonObject], context: Option[ToolContext[F]] = None)(using
      F: ApplicativeError[F, Throwable],
      A: Async[F]
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
      annotations: Option[ToolAnnotations] = None,
      icons: Option[List[Icon]] = None
  )(handler: (Input, ToolContext[F]) => F[List[Content]])(using
      schema: McpSchema[Input],
      F: cats.Functor[F]
  ): ToolDef[F, Input, Nothing] =
    ToolDef[F, Input, Nothing](
      name = name,
      description = description,
      handler = (input, ctx) => F.map(handler(input, ctx))(ToolOutput.Unstructured(_)),
      annotations = annotations,
      icons = icons
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
      annotations: Option[ToolAnnotations] = None,
      icons: Option[List[Icon]] = None
  )(handler: (Input, ToolContext[F]) => F[Output])(using
      inputSchema: McpSchema[Input],
      outputSchema: McpSchema[Output],
      F: cats.Functor[F]
  ): ToolDef[F, Input, Output] =
    ToolDef[F, Input, Output](
      name = name,
      description = description,
      handler = (input, ctx) => F.map(handler(input, ctx))(ToolOutput.Structured(_)),
      annotations = annotations,
      icons = icons
    )(using inputSchema, Some(outputSchema))
}

/** Resource content encoding mode.
  *
  * Determines how the resource output is serialized in the response.
  */
enum ResourceEncoding {

  /** Text/JSON content - output is JSON-encoded */
  case Text

  /** Binary content - output (Array[Byte]) is base64-encoded */
  case Binary
}

/** A resource definition with typed output.
  *
  * This is an applicative data structure for resources:
  *   - Specification (URI, name, description, MIME type, metadata)
  *   - Type-safe handler (ResourceContext[F] => F[Output])
  *
  * The handler receives a ResourceContext providing logging and roots access. The output is automatically serialized based on the encoding
  * mode.
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @tparam Output
  *   Output type (must have Encoder for Text, or Array[Byte] for Binary)
  * @param uri
  *   Unique URI for the resource
  * @param name
  *   Human-readable name
  * @param title
  *   Optional display title (if not provided, name is used for display)
  * @param description
  *   Optional description
  * @param mimeType
  *   Optional MIME type (defaults to "application/json" for Text, "application/octet-stream" for Binary)
  * @param annotations
  *   Optional annotations (audience, priority, lastModified)
  * @param size
  *   Optional size in bytes (hint for clients)
  * @param icons
  *   Optional icons for UI display
  * @param encoding
  *   Content encoding mode (Text or Binary)
  * @param handler
  *   Function to read the resource, receives ResourceContext for logging/roots access
  * @param updates
  *   Optional stream that emits when the resource content changes. Subscribed clients will receive `notifications/resources/updated`
  *   notifications. The stream emits Unit - the library maps it to the resource URI.
  * @param outputEncoder
  *   Encoder for serializing output (for Text encoding)
  */
case class ResourceDef[F[_], Output](
    uri: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    annotations: Option[Annotations] = None,
    size: Option[Long] = None,
    icons: Option[List[Icon]] = None,
    encoding: ResourceEncoding = ResourceEncoding.Text,
    handler: ResourceContext[F] => F[Output],
    updates: fs2.Stream[F, Unit] = fs2.Stream.empty
)(using val outputEncoder: Encoder[Output]) {

  /** Convert to protocol Resource type for listing */
  def toResource: ProtocolResource =
    ProtocolResource(
      uri = uri,
      name = name,
      title = title,
      description = description,
      mimeType = mimeType,
      annotations = annotations,
      size = size,
      icons = icons
    )

  /** Read the resource contents, handling encoding internally.
    *
    * @param ctx
    *   Resource context providing logging and roots access
    */
  def read(ctx: ResourceContext[F])(using F: ApplicativeError[F, Throwable]): F[ReadResourceResult] =
    handler(ctx)
      .map { output =>
        encoding match {
          case ResourceEncoding.Text =>
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
          case ResourceEncoding.Binary =>
            // For binary encoding, Output should be Array[Byte]
            val bytes = output.asInstanceOf[Array[Byte]]
            val base64 = java.util.Base64.getEncoder.encodeToString(bytes)
            ReadResourceResult(
              contents = List(
                ResourceContents.Blob(
                  uri = uri,
                  blob = base64,
                  mimeType = mimeType.orElse(Some("application/octet-stream"))
                )
              )
            )
        }
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

/** A resource template definition for dynamic parameterized resources.
  *
  * Resource templates use URI patterns (RFC 6570) to match requests and dynamically resolve them to concrete resources. The resolver
  * function receives extracted template variables and returns an optional ResourceDef.
  *
  * Example:
  * {{{
  * ResourceTemplateDef[IO](
  *   uriTemplate = "file:///{path}",
  *   name = "Workspace Files",
  *   description = Some("Read any file in workspace")
  * ) { (params, ctx) =>
  *   val path = params.getOrElse("path", "")
  *   IO.pure(Some(
  *     ResourceDef[IO, String](
  *       uri = s"file:///$path",
  *       name = path,
  *       handler = _ => IO(Files.readString(Paths.get(path)))
  *     )
  *   ))
  * }
  * }}}
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @param uriTemplate
  *   RFC 6570 URI template pattern (e.g., "file:///{path}")
  * @param name
  *   Human-readable name for this template
  * @param title
  *   Optional display title
  * @param description
  *   Optional description
  * @param mimeType
  *   Optional default MIME type for resolved resources
  * @param annotations
  *   Optional annotations (audience, priority)
  * @param icons
  *   Optional icons for UI display
  * @param resolver
  *   Function that resolves template variables to a concrete ResourceDef
  * @param updates
  *   Stream that emits ResourceUri values when resources matching this template change. Subscribed clients will receive
  *   `notifications/resources/updated` notifications.
  */
case class ResourceTemplateDef[F[_]](
    uriTemplate: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    annotations: Option[Annotations] = None,
    icons: Option[List[Icon]] = None,
    resolver: (Map[String, String], ResourceContext[F]) => F[Option[ResourceDef[F, ?]]],
    updates: fs2.Stream[F, ResourceUri] = fs2.Stream.empty
) {

  /** The parsed URI template. Parsing is done once at construction time. */
  lazy val parsedTemplate: Either[String, UriTemplate] =
    UriTemplate.parse(uriTemplate)

  /** Check if a URI matches this template pattern. */
  def matches(uri: String): Boolean =
    parsedTemplate.exists(_.matches(uri))

  /** Extract variables from a URI that matches this template.
    *
    * @param uri
    *   The URI to extract from
    * @return
    *   Some(variables) if the URI matches, None otherwise
    */
  def extract(uri: String): Option[Map[String, String]] =
    parsedTemplate.toOption.flatMap(_.extractString(uri))

  /** Convert to protocol ResourceTemplate type for listing. */
  def toResourceTemplate: ResourceTemplate =
    ResourceTemplate(
      uriTemplate = uriTemplate,
      name = name,
      title = title,
      description = description,
      mimeType = mimeType,
      annotations = annotations,
      icons = icons
    )

  /** Resolve a URI to a concrete ResourceDef.
    *
    * @param uri
    *   The URI to resolve
    * @param ctx
    *   Resource context for logging and roots access
    * @return
    *   Some(ResourceDef) if the URI matches and resolves, None otherwise
    */
  def resolve(uri: String, ctx: ResourceContext[F])(using F: cats.Monad[F]): F[Option[ResourceDef[F, ?]]] =
    extract(uri) match {
      case Some(vars) => resolver(vars, ctx)
      case None       => F.pure(None)
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
    icons: Option[List[Icon]] = None,
    handler: Args => F[List[PromptMessage]]
)(using val argsDecoder: Decoder[Args]) {

  /** Convert to protocol Prompt type for listing */
  def toPrompt: Prompt =
    Prompt(
      name = name,
      description = description,
      arguments = if arguments.isEmpty then None else Some(arguments),
      icons = icons
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

object PromptDef {

  /** Create a prompt with arguments derived from McpSchema.
    *
    * This factory method extracts argument specifications from the McpSchema's JSON schema, including descriptions from @description
    * annotations on case class fields.
    *
    * Example:
    * {{{
    * @description("Translation prompt arguments")
    * case class Args(
    *   @description("Text to translate") text: String,
    *   @description("Target language") language: String
    * ) derives Codec.AsObject
    * object Args {
    *   given McpSchema[Args] = McpSchema.derived
    * }
    *
    * PromptDef.derived[IO, Args](
    *   name = "translate",
    *   description = Some("Translate text to another language")
    * ) { args =>
    *   IO.pure(List(PromptMessage(...)))
    * }
    * }}}
    *
    * @param name
    *   Unique name for the prompt
    * @param description
    *   Optional description
    * @param icons
    *   Optional icons for UI display
    * @param handler
    *   Function to generate prompt messages from typed arguments
    */
  def derived[F[_], Args](
      name: String,
      description: Option[String] = None,
      icons: Option[List[Icon]] = None
  )(handler: Args => F[List[PromptMessage]])(using schema: McpSchema[Args]): PromptDef[F, Args] = {
    val arguments = extractArguments(schema.jsonSchema)
    new PromptDef[F, Args](
      name = name,
      description = description,
      arguments = arguments,
      icons = icons,
      handler = handler
    )(using schema.decoder)
  }

  /** Extract PromptArgument list from a JSON schema. */
  private def extractArguments(schema: JsonSchemaType.ObjectSchema): List[PromptArgument] = {
    val requiredFields = schema.required.getOrElse(Nil).toSet
    schema.properties
      .getOrElse(Map.empty)
      .map { case (fieldName, fieldSchema) =>
        PromptArgument(
          name = fieldName,
          description = extractDescription(fieldSchema),
          required = Some(requiredFields.contains(fieldName))
        )
      }
      .toList
      .sortBy(_.name) // Consistent ordering
  }

  /** Extract description from a JsonSchemaType. */
  private def extractDescription(schema: JsonSchemaType): Option[String] =
    schema match {
      case s: JsonSchemaType.StringSchema  => s.description
      case s: JsonSchemaType.IntegerSchema => s.description
      case s: JsonSchemaType.NumberSchema  => s.description
      case s: JsonSchemaType.BooleanSchema => s.description
      case s: JsonSchemaType.ArraySchema   => s.description
      case s: JsonSchemaType.ObjectSchema  => s.description
      case _                               => None
    }
}

/** A completion provider for prompt arguments or resource template variables.
  *
  * Completion providers enable auto-completion for:
  *   - Prompt arguments (e.g., completing language names for a "translate" prompt)
  *   - Resource template variables (e.g., completing file paths)
  *
  * Each provider is associated with a specific reference (prompt or resource template) and handles completion requests for arguments of
  * that reference.
  *
  * @tparam F
  *   Effect type (e.g., IO)
  * @param ref
  *   The completion reference (prompt or resource template)
  * @param handler
  *   Function that takes (argument name, current value) and returns completions
  */
case class CompletionDef[F[_]](
    ref: CompletionReference,
    handler: (String, String) => F[CompletionCompletion]
) {

  /** Complete an argument value.
    *
    * @param argument
    *   The argument being completed (name and current value)
    * @return
    *   Completion suggestions
    */
  def complete(argument: CompletionArgument)(using F: ApplicativeError[F, Throwable]): F[CompleteResult] =
    handler(argument.name, argument.value)
      .map(completion => CompleteResult(completion = completion))
      .handleError { error =>
        CompleteResult(completion = CompletionCompletion(values = Nil))
      }
}

object CompletionDef {

  /** Create a completion provider for a prompt's arguments.
    *
    * @param promptName
    *   The name of the prompt to provide completions for
    * @param handler
    *   Function taking (argument name, current value) returning completions
    */
  def forPrompt[F[_]](
      promptName: String,
      handler: (String, String) => F[CompletionCompletion]
  ): CompletionDef[F] =
    CompletionDef(
      ref = CompletionReference.Prompt(name = promptName),
      handler = handler
    )

  /** Create a completion provider for a resource template's variables.
    *
    * @param uriTemplate
    *   The parsed URI template (use UriTemplate.parse to create)
    * @param handler
    *   Function taking (variable name, current value) returning completions
    */
  def forResourceTemplate[F[_]](
      uriTemplate: UriTemplate,
      handler: (String, String) => F[CompletionCompletion]
  ): CompletionDef[F] =
    CompletionDef(
      ref = CompletionReference.ResourceTemplate(uri = uriTemplate.template),
      handler = handler
    )
}
