package everything.tools

import cats.effect.Async
import cats.syntax.all.*
import mcp.protocol.{Content, ToolAnnotations}
import mcp.server.{InputDef, InputField, ToolDef}

import scala.concurrent.duration.*

/** Long running operation tool - demonstrates progress reporting.
  *
  * Runs a simulated operation with configurable duration and steps, reporting progress at each step.
  */
object LongRunningTool {

  type Input = (duration: Option[Int], steps: Option[Int])
  given InputDef[Input] = InputDef[Input](
    duration = InputField[Option[Int]]("Duration of the operation in seconds"),
    steps = InputField[Option[Int]]("Number of steps in the operation")
  )

  def apply[F[_]](using F: Async[F]): ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "trigger-long-running-operation",
      description = Some("Demonstrates a long running operation with progress updates"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Long Running Operation"),
          readOnlyHint = Some(true),
          idempotentHint = Some(false),
          openWorldHint = Some(false)
        )
      )
    ) { (input, ctx) =>
      val duration = input.duration.getOrElse(10)
      val steps = input.steps.getOrElse(5)
      val stepDuration = (duration.toDouble / steps).seconds

      def runStep(step: Int): F[Unit] =
        F.sleep(stepDuration) *>
          ctx.reportProgress(step.toDouble, Some(steps.toDouble), Some(s"Completed step $step of $steps"))

      (1 to steps).toList
        .traverse_(runStep)
        .as(
          List(Content.Text(s"Long running operation completed. Duration: $duration seconds, Steps: $steps."))
        )
    }
}
