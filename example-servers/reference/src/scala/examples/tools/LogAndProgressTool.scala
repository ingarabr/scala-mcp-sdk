package examples.tools

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import mcp.protocol.Content
import mcp.protocol.LoggingLevel.info
import mcp.schema.{McpSchema, description}
import mcp.server.ToolDef

import scala.concurrent.duration.DurationInt

/** Echo tool - echoes back the input message.
  *
  * This demonstrates a simple tool with string input and output, using automatic schema derivation from Scaladoc comments.
  */
object LogAndProgressTool {

  @description("Input for echo operation with time progress and logging")
  case class Input(
      @description("The message to echo back")
      message: String
  ) derives Codec.AsObject
  object Input {
    given McpSchema[Input] = McpSchema.derived
  }

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
