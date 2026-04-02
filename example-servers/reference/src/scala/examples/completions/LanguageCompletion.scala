package examples.completions

import cats.effect.*
import mcp.protocol.CompletionCompletion
import mcp.server.CompletionDef

/** Language completion provider for the translate prompt.
  *
  * This demonstrates argument completion for prompts. When a client requests completions for the "translate" prompt's "language" argument,
  * this provider returns matching language names.
  */
object LanguageCompletion {

  private val languages = List(
    "Arabic",
    "Chinese",
    "Dutch",
    "English",
    "French",
    "German",
    "Hindi",
    "Italian",
    "Japanese",
    "Korean",
    "Portuguese",
    "Russian",
    "Spanish",
    "Swedish",
    "Turkish"
  )

  def apply[F[_]: Async]: CompletionDef[F] =
    CompletionDef.forPrompt[F](
      promptName = "translate",
      handler = { (argName, currentValue, _) =>
        Async[F].pure {
          if argName == "language" then {
            val matches = languages
              .filter(_.toLowerCase.startsWith(currentValue.toLowerCase))
              .take(10)
            CompletionCompletion(
              values = matches,
              total = Some(languages.count(_.toLowerCase.startsWith(currentValue.toLowerCase))),
              hasMore = Some(matches.size < languages.count(_.toLowerCase.startsWith(currentValue.toLowerCase)))
            )
          } else {
            CompletionCompletion(values = Nil)
          }
        }
      }
    )
}
