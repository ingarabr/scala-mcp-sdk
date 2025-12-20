package examples.prompts

import cats.effect.*
import io.circe.*
import io.circe.generic.semiauto.*
import mcp.protocol.{Content, Icon, IconTheme, PromptArgument, PromptMessage, Role}
import mcp.server.PromptDef

/** Greeting prompt - generates a friendly greeting.
  *
  * This demonstrates a prompt that takes arguments and generates messages.
  */
object GreetingPrompt {

  case class Args(name: String) derives Codec.AsObject

  // Speech bubble icon for greeting/chat
  // Original: <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
  private val greetingIconLight =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgc3Ryb2tlLXdpZHRoPSIyIj48cGF0aCBkPSJNMjEgMTVhMiAyIDAgMCAxLTIgMkg3bC00IDRWNWE2IDIgMCAwIDEgMi0yaDE0YTIgMiAwIDAgMSAyIDJ6Ii8+PC9zdmc+"

  // Dark theme variant with white stroke
  // Original: <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
  private val greetingIconDark =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IndoaXRlIiBzdHJva2Utd2lkdGg9IjIiPjxwYXRoIGQ9Ik0yMSAxNWEyIDIgMCAwIDEtMiAySDdsLTQgNFY1YTIgMiAwIDAgMSAyLTJoMTRhMiAyIDAgMCAxIDIgMnoiLz48L3N2Zz4="

  def apply[F[_]: Async]: PromptDef[F, Args] =
    PromptDef[F, Args](
      name = "greeting",
      description = Some("Generate a friendly greeting"),
      arguments = List(
        PromptArgument(
          name = "name",
          description = Some("The name to greet"),
          required = Some(true)
        )
      ),
      icons = Some(
        List(
          Icon(src = greetingIconLight, mimeType = Some("image/svg+xml"), sizes = Some(List("any")), theme = Some(IconTheme.light)),
          Icon(src = greetingIconDark, mimeType = Some("image/svg+xml"), sizes = Some(List("any")), theme = Some(IconTheme.dark))
        )
      ),
      handler = args =>
        Async[F].pure(
          List(
            PromptMessage(
              role = Role.user,
              content = Content.Text(s"Hello, ${args.name}! How can I help you today?")
            )
          )
        )
    )
}
