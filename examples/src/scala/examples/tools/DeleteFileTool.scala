package examples.tools

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import mcp.protocol.ToolAnnotations
import mcp.schema.{McpSchema, description}
import mcp.server.*

object DeleteFileTool {

  @description("File to delete")
  case class Input(
      @description("Path to the file")
      path: String
  ) derives Codec.AsObject,
        McpSchema

  case class Output(
      deleted: Boolean,
      message: String
  ) derives Codec.AsObject
  object Output {
    given McpSchema[Output] = McpSchema.derived
  }

  private val confirmFields = (
    FormField.boolean.required("confirm", title = Some("Confirm"), description = Some("Set to true to confirm deletion")),
    FormField.oneOf.optional("reason", List("cleanup", "outdated", "duplicate", "other"), title = Some("Reason")),
    FormField.string.required("confirmedBy", title = Some("Confirmed by"), description = Some("Your name"))
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef.structured[F, Input, Output](
      name = "delete_file",
      description = Some("Delete a file (with confirmation)"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Delete File"),
          readOnlyHint = Some(false),
          destructiveHint = Some(true)
        )
      )
    ) { (input, ctx) =>
      ctx.elicitationCapability.fold(
        notSupported = Async[F].pure(Output(false, "Elicitation not supported")),
        supported = ctx.elicit(s"Delete ${input.path}?", confirmFields).map {
          case ElicitResult.Accepted((confirmed, reason, confirmedBy)) =>
            if confirmed then Output(true, s"Deleted ${input.path} by $confirmedBy" + reason.fold("")(r => s" ($r)"))
            else Output(false, "User did not confirm")
          case ElicitResult.Declined  => Output(false, "User declined")
          case ElicitResult.Cancelled => Output(false, "Cancelled")
        }
      )
    }
}
