---
sidebar_position: 1
---

# Sampling

Sampling lets your server request LLM completions from the client. This enables agentic behaviors without needing your
own API keys - the client controls model access.

See [MCP sampling](https://modelcontextprotocol.io/docs/concepts/sampling) for the full concept.

## Requesting a Completion

Use the context's `sample` method:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.{Content, CreateMessageResult, Role, SamplingMessage}
import mcp.server.{SampleResult, ToolContext}

def analyze(ctx: ToolContext[IO]): IO[List[Content]] = {
  val messages = List(
    SamplingMessage(Role.user, List(Content.Text("Summarize this data: ...")))
  )

  ctx.sample(messages, maxTokens = 500).map {
    case SampleResult.Success(result) =>
      val text = result.content.collect { case Content.Text(t, _, _) => t }.mkString
      List(Content.Text(s"Analysis: $text"))
    case SampleResult.Failed(reason) =>
      List(Content.Text(s"Sampling failed: $reason"))
    case SampleResult.NotSupported =>
      List(Content.Text("Client doesn't support sampling"))
  }
}
```

## Model Preferences

Don't hardcode model names. Instead, express preferences and let the client choose:

```scala mdoc:compile-only
import mcp.protocol.{ModelHint, ModelPreferences, SamplingMessage}
import mcp.server.ToolContext
import cats.effect.IO

def sampleWithPreferences(ctx: ToolContext[IO], messages: List[SamplingMessage]): Unit = {
  ctx.sample(
    messages = messages,
    maxTokens = 100,
    modelPreferences = Some(ModelPreferences(
      hints = Some(List(ModelHint(name = Some("claude-3-sonnet")), ModelHint(name = Some("claude")))),
      intelligencePriority = Some(0.8),
      speedPriority = Some(0.5),
      costPriority = Some(0.3)
    ))
  )
}
```

### Priority Scales (0.0 to 1.0)

| Priority               | High value means...        |
|------------------------|----------------------------|
| `intelligencePriority` | Prefer more capable models |
| `speedPriority`        | Prefer faster models       |
| `costPriority`         | Prefer cheaper models      |

### Hints

Hints are advisory - the client may map them to equivalent models:

```scala mdoc:compile-only
import mcp.protocol.ModelHint

ModelHint(name = Some("claude-3-sonnet")) // Prefer Sonnet-class
ModelHint(name = Some("claude"))          // Fall back to any Claude
```

## System Prompt

Provide context for the model:

```scala mdoc:compile-only
import mcp.protocol.SamplingMessage
import mcp.server.ToolContext
import cats.effect.IO

def sampleWithSystem(ctx: ToolContext[IO], messages: List[SamplingMessage]): Unit = {
  ctx.sample(
    messages = messages,
    maxTokens = 1000,
    systemPrompt = Some("You are a senior code reviewer. Be constructive.")
  )
}
```

## Checking Client Support

Sampling requires client capability. Check before using:

```scala mdoc:compile-only
import cats.effect.IO
import mcp.protocol.Content
import mcp.server.{SamplingCapability, ToolContext}

def checkSampling(ctx: ToolContext[IO]): IO[List[Content]] = {
  ctx.samplingCapability match {
    case SamplingCapability.Supported =>
      // Safe to call ctx.sample(...)
      IO.pure(List(Content.Text("Sampling supported")))
    case SamplingCapability.NotSupported =>
      IO.pure(List(Content.Text("Client doesn't support sampling")))
  }
}
```

## Use Cases

- **Agentic tools** - Let tools make decisions using LLM reasoning
- **Content generation** - Generate text, summaries, translations
- **Analysis** - Have the LLM interpret data your server provides
