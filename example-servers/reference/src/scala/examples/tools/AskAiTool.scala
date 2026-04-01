package examples.tools

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import mcp.protocol.{Content, Role, SamplingMessage}
import mcp.server.{InputDef, InputField, OutputDef, SampleResult, ToolDef}

object AskAiTool {

  type Input = (question: String, context: Option[String])
  given InputDef[Input] = InputDef[Input](
    question = InputField[String]("The question to ask"),
    context = InputField[Option[String]]("Optional context to include")
  )

  case class Output(
      success: Boolean,
      answer: Option[String],
      model: Option[String],
      error: Option[String]
  ) derives Codec.AsObject
  given OutputDef[Output] = OutputDef[Output](
    success = InputField[Boolean]("Whether the request succeeded"),
    answer = InputField[Option[String]]("The AI's answer"),
    model = InputField[Option[String]]("Model used"),
    error = InputField[Option[String]]("Error message if failed")
  )

  def apply[F[_]: Async]: ToolDef[F, Input, Output] =
    ToolDef.structured[F, Input, Output](
      name = "ask_ai",
      description = Some("Ask the client's AI model a question and get a response")
    ) { (input, ctx) =>
      val userContent = input.context match {
        case Some(context) => s"Context:\n$context\n\nQuestion: ${input.question}"
        case None          => input.question
      }
      val messages = List(SamplingMessage(Role.user, List(Content.Text(userContent))))

      ctx
        .sample(
          messages = messages,
          maxTokens = 1000,
          systemPrompt = Some("You are a helpful assistant. Answer questions concisely and accurately.")
        )
        .map {
          case SampleResult.Success(result) =>
            val answer = result.content.headOption match {
              case Some(Content.Text(text, _, _)) => text
              case other                          => other.toString
            }
            Output(true, Some(answer), Some(result.model), None)
          case SampleResult.Failed(reason) =>
            Output(false, None, None, Some(reason))
          case SampleResult.NotSupported =>
            Output(false, None, None, Some("Sampling not supported by client"))
        }
    }
}
