package examples.prompts

import cats.effect.*
import mcp.protocol.{Content, PromptMessage, Role}
import mcp.server.{InputDef, InputField, PromptDef}

object TranslatePrompt {

  type Args = (text: String, language: String)
  given InputDef[Args] = InputDef[Args](
    text = InputField[String]("The text to translate"),
    language = InputField[String]("Target language (e.g., Spanish, French, German)")
  )

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
