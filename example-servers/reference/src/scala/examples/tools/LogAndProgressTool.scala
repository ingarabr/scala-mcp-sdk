package examples.tools

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import mcp.protocol.Content
import mcp.protocol.LoggingLevel.info
import mcp.server.{InputDef, InputField, ToolDef}

import scala.concurrent.duration.DurationInt

object LogAndProgressTool {

  type Input = (message: String, steps: Option[Int])
  given InputDef[Input] = InputDef[Input](
    message = InputField[String]("The message to echo back"),
    steps = InputField[Option[Int]]("Number of progress steps (default 3)")
  )

  def apply[F[_]](using F: Async[F]): ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "progressing",
      description = Some("Echo back the input message")
    ) { (input, ctx) =>
      for {
        _ <- ctx.log(info, Json.fromString(s"Starting to echo: ${input.message.take(5)}..."), Some("tool"))
        _ <- ctx.reportProgress(1, Some(3))
        _ <- F.sleep(1.second)
        _ <- ctx.log(info, Json.fromString("progressing..."), Some("tool"))
        _ <- ctx.reportProgress(2, Some(3))
        _ <- F.sleep(1.second)
        _ <- ctx.reportProgress(3, Some(3))
        _ <- F.sleep(200.millis)
      } yield List(Content.Text(s"Echo: ${input.message}"))
    }
}
