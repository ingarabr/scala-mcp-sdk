package everything.tools

import cats.effect.Async
import cats.syntax.all.*
import mcp.protocol.{Content, CreateMessageResult, Role, SamplingMessage, ToolAnnotations}
import mcp.server.{InputDef, InputField, SampleResult, ToolDef}

/** Sampling tool - demonstrates requesting LLM sampling from the client.
  *
  * Uses the client's LLM to generate a response to a prompt.
  */
object SamplingTool {

  type Input = (prompt: String, maxTokens: Option[Int])
  given InputDef[Input] = InputDef[Input](
    prompt = InputField[String]("The prompt to send to the LLM"),
    maxTokens = InputField[Option[Int]]("Maximum number of tokens to generate")
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "trigger-sampling-request",
      description = Some("Triggers a request from the server for LLM sampling"),
      annotations = Some(
        ToolAnnotations(
          title = Some("Trigger Sampling Request"),
          readOnlyHint = Some(true),
          idempotentHint = Some(false),
          openWorldHint = Some(true)
        )
      )
    ) { (input, ctx) =>
      val messages = List(
        SamplingMessage(
          role = Role.user,
          content = List(Content.Text(s"Everything server context: ${input.prompt}"))
        )
      )

      ctx
        .sample(
          messages = messages,
          maxTokens = input.maxTokens.getOrElse(100),
          systemPrompt = Some("You are a helpful test server.")
        )
        .map { (sampleResult: SampleResult[CreateMessageResult]) =>
          sampleResult match {
            case SampleResult.Success(result) =>
              val contentText = result.content.collect { case Content.Text(t, _, _) => t }.mkString("\n")
              List(Content.Text(s"LLM sampling result (model: ${result.model}):\n$contentText"))
            case SampleResult.Failed(reason) =>
              List(Content.Text(s"Sampling failed: $reason"))
            case SampleResult.NotSupported =>
              List(Content.Text("Sampling not supported by client"))
          }
        }
    }
}
