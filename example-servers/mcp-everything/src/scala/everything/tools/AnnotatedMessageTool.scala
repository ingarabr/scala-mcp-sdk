package everything.tools

import cats.effect.Async
import io.circe.Codec
import mcp.protocol.{Annotations, Content, Role, ToolAnnotations}
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

/** Annotated message tool - demonstrates content with annotations.
  *
  * Returns a message with priority and audience annotations.
  */
object AnnotatedMessageTool {

  @description("Input for annotated message")
  case class Input(
      @description("The message to annotate")
      message: String,
      @description("Priority level (0.0 to 1.0)")
      priority: Option[Double],
      @description("Target audience: user, assistant, or both")
      audience: Option[String]
  ) derives Codec.AsObject,
        McpSchema

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
