package examples.tools

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import mcp.protocol.ToolAnnotations
import mcp.server.*

object DeleteFileTool {

  type Input = (path: String, force: Option[Boolean])
  given InputDef[Input] = InputDef[Input](
    path = InputField[String]("Path to the file"),
    force = InputField[Option[Boolean]]("Skip confirmation if true")
  )

  case class Output(deleted: Boolean, message: String) derives Codec.AsObject
  given OutputDef[Output] = OutputDef[Output](
    deleted = InputField[Boolean]("Whether the file was deleted"),
    message = InputField[String]("Result message")
  )

  // Elicit form — same InputField, same InputDef pattern as tool input
  type ConfirmForm = (confirm: Boolean, reason: Option[String], confirmedBy: String)
  private val confirmDef = InputDef[ConfirmForm](
    confirm = InputField[Boolean](title = Some("Confirm"), description = Some("Set to true to confirm deletion")),
    reason = InputField[Option[String]](title = Some("Reason")),
    confirmedBy = InputField[String](title = Some("Confirmed by"), description = Some("Your name"))
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
      if input.force.getOrElse(false) then Async[F].pure(Output(true, s"Force-deleted ${input.path}"))
      else
        ctx.elicitationCapability.fold(
          notSupported = Async[F].pure(Output(false, "Elicitation not supported")),
          supported = ctx.elicit(s"Delete ${input.path}?", confirmDef).map {
            case ElicitResult.Accepted(form) =>
              if form.confirm then Output(true, s"Deleted ${input.path} by ${form.confirmedBy}" + form.reason.fold("")(r => s" ($r)"))
              else Output(false, "User did not confirm")
            case ElicitResult.Declined  => Output(false, "User declined")
            case ElicitResult.Cancelled => Output(false, "Cancelled")
          }
        )
    }
}
