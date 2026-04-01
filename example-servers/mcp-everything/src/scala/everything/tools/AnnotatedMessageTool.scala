package everything.tools

import cats.effect.Async
import mcp.protocol.{Annotations, Content, Role, ToolAnnotations}
import mcp.server.{InputDef, InputField, ToolDef}

/** Annotated message tool - demonstrates content with annotations.
  *
  * Returns a message with priority and audience annotations.
  */
object AnnotatedMessageTool {

  type Input = (message: String, priority: Option[Double], audience: Option[String])
  given InputDef[Input] = InputDef[Input](
    message = InputField[String]("The message to annotate"),
    priority = InputField[Option[Double]]("Priority level (0.0 to 1.0)"),
    audience = InputField[Option[String]]("Target audience: user, assistant, or both")
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "get-annotated-message",
      description = Some("Returns a message with annotations for priority and audience"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Get Annotated Message"),
          readOnlyHint = Some(true),
          idempotentHint = Some(true),
          openWorldHint = Some(false)
        )
      )
    ) { (input, _) =>
      val audienceList = input.audience match {
        case Some("user")      => Some(List(Role.user))
        case Some("assistant") => Some(List(Role.assistant))
        case Some("both")      => Some(List(Role.user, Role.assistant))
        case _                 => None
      }

      val contentAnnotations = Annotations(
        priority = input.priority,
        audience = audienceList
      )

      Async[F].pure(
        List(
          Content.Text(
            text = input.message,
            annotations = Some(contentAnnotations)
          )
        )
      )
    }
}
