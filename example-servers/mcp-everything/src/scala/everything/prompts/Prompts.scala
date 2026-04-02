package everything.prompts

import cats.effect.Async
import io.circe.Decoder
import mcp.protocol.{CompletionCompletion, Content, JsonSchemaType, PromptMessage, ResourceContents, Role}
import mcp.server.{CompletionDef, InputDef, InputField, PromptDef}

/** Simple prompt with no arguments. */
object SimplePrompt {

  case class Args() derives Decoder

  def apply[F[_]: Async]: PromptDef[F, Args] =
    PromptDef[F, Args](
      name = "simple-prompt",
      description = Some("A prompt with no arguments"),
      arguments = Nil,
      handler = _ =>
        Async[F].pure(
          List(
            PromptMessage(
              role = Role.user,
              content = Content.Text("This is a simple prompt without arguments.")
            )
          )
        )
    )
}

/** Prompt with arguments - demonstrates typed prompt parameters. */
object ArgumentsPrompt {

  type Args = (name: String, style: Option[String])
  given InputDef[Args] = InputDef[Args](
    name = InputField[String]("Name of the person to greet"),
    style = InputField[Option[String]]("Style of greeting: formal or casual")
  )

  def apply[F[_]: Async]: PromptDef[F, Args] =
    PromptDef.derived[F, Args](
      name = "arguments-prompt",
      description = Some("A prompt that takes arguments for personalized greetings")
    ) { args =>
      val greeting = args.style match {
        case Some("formal") => s"Good day, ${args.name}. How may I assist you today?"
        case Some("casual") => s"Hey ${args.name}! What's up?"
        case _              => s"Hello, ${args.name}!"
      }

      Async[F].pure(
        List(
          PromptMessage(
            role = Role.user,
            content = Content.Text(greeting)
          )
        )
      )
    }
}

/** Completion provider for the arguments prompt. */
object ArgumentsPromptCompletion {

  private val styles = List("formal", "casual", "friendly", "professional")

  def apply[F[_]: Async]: CompletionDef[F] =
    CompletionDef.forPrompt[F](
      promptName = "arguments-prompt",
      handler = (argName, value, _) =>
        Async[F].pure {
          argName match {
            case "style" =>
              val matches = styles.filter(_.startsWith(value.toLowerCase))
              CompletionCompletion(values = matches)
            case _ =>
              CompletionCompletion(values = Nil)
          }
        }
    )
}

/** Prompt with embedded resource - demonstrates including resource content in prompts. */
object EmbeddedResourcePrompt {

  case class Args(resourceId: Option[Int]) derives Decoder
  given InputDef[Args] = InputDef.raw(
    JsonSchemaType.ObjectSchema(
      properties = Some(Map("resourceId" -> JsonSchemaType.IntegerSchema(description = Some("ID of the resource to embed"))))
    ),
    summon[Decoder[Args]]
  )

  def apply[F[_]: Async]: PromptDef[F, Args] =
    PromptDef.derived[F, Args](
      name = "embedded-resource-prompt",
      description = Some("A prompt that includes an embedded resource reference")
    ) { args =>
      val resourceId = args.resourceId.getOrElse(1)
      val resourceUri = s"demo://resource/dynamic/text/$resourceId"

      Async[F].pure(
        List(
          PromptMessage(
            role = Role.user,
            content = Content.Text(s"Please analyze the following resource:")
          ),
          PromptMessage(
            role = Role.user,
            content = Content.Resource(
              resource = ResourceContents.Text(
                uri = resourceUri,
                text = s"Resource $resourceId content would be loaded here",
                mimeType = Some("text/plain")
              )
            )
          )
        )
      )
    }
}
