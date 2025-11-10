package examples.prompts

import cats.effect.*
import io.circe.*
import io.circe.generic.semiauto.*
import mcp.protocol.{Content, PromptArgument, PromptMessage, Role}
import mcp.server.PromptDef

/** Greeting prompt - generates a friendly greeting.
  *
  * This demonstrates a prompt that takes arguments and generates messages.
  */
object GreetingPrompt {

  case class Args(name: String) derives Codec.AsObject

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
