package examples.prompts

import cats.effect.*
import io.circe.*
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.schema.{McpSchema, description}
import mcp.server.PromptDef

/** Translate prompt - generates a translation request.
  *
  * This demonstrates a prompt with completable arguments derived from McpSchema. The "language" argument can be auto-completed using the
  * LanguageCompletion provider.
  */
object TranslatePrompt {

  case class Args(
      @description("The text to translate")
      text: String,
      @description("Target language (e.g., Spanish, French, German)")
      language: String
  ) derives Codec.AsObject
  object Args {
    given McpSchema[Args] = McpSchema.derived
  }

  def apply[F[_]: Async]: PromptDef[F, Args] =
    PromptDef.derived[F, Args](
      name = "translate",
      description = Some("Translate text to another language")
    ) { args =>
      Async[F].pure(
        List(
          PromptMessage(
            role = Role.user,
            content = Content.Text(s"Please translate the following text to ${args.language}:\n\n${args.text}")
          )
        )
      )
    }
}
