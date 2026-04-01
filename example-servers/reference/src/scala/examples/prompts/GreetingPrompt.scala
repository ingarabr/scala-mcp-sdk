package examples.prompts

import cats.effect.*
import mcp.protocol.{Content, Icon, IconTheme, PromptMessage, Role}
import mcp.server.{InputDef, InputField, PromptDef}

object GreetingPrompt {

  type Args = (name: String, formal: Option[Boolean])
  given InputDef[Args] = InputDef[Args](
    name = InputField[String]("The name to greet"),
    formal = InputField[Option[Boolean]]("Use formal greeting style")
  )

  // Speech bubble icon for greeting/chat
  // Original: <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
  private val greetingIconLight =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgc3Ryb2tlLXdpZHRoPSIyIj48cGF0aCBkPSJNMjEgMTVhMiAyIDAgMCAxLTIgMkg3bC00IDRWNWE2IDIgMCAwIDEgMi0yaDE0YTIgMiAwIDAgMSAyIDJ6Ii8+PC9zdmc+"

  // Dark theme variant with white stroke
  // Original: <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
  private val greetingIconDark =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IndoaXRlIiBzdHJva2Utd2lkdGg9IjIiPjxwYXRoIGQ9Ik0yMSAxNWEyIDIgMCAwIDEtMiAySDdsLTQgNFY1YTIgMiAwIDAgMSAyLTJoMTRhMiAyIDAgMCAxIDIgMnoiLz48L3N2Zz4="

  def apply[F[_]: Async]: PromptDef[F, Args] =
    PromptDef.derived[F, Args](
      name = "greeting",
      description = Some("Generate a friendly greeting"),
      icons = Some(
        List(
          Icon(src = greetingIconLight, mimeType = Some("image/svg+xml"), sizes = Some(List("any")), theme = Some(IconTheme.light)),
          Icon(src = greetingIconDark, mimeType = Some("image/svg+xml"), sizes = Some(List("any")), theme = Some(IconTheme.dark))
        )
      )
    ) { args =>
      val greeting =
        if args.formal.getOrElse(false) then s"Good day, ${args.name}. How may I assist you?"
        else s"Hello, ${args.name}! How can I help you today?"
      Async[F].pure(
        List(PromptMessage(role = Role.user, content = Content.Text(greeting)))
      )
    }
}
