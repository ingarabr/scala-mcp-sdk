package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** A prompt or prompt template that the server offers.
  */
case class Prompt(
    /** The name of the prompt or prompt template. */
    name: String,
    /** An optional description of what this prompt provides. */
    description: Option[String] = None,
    /** A list of arguments to use for templating the prompt. */
    arguments: Option[List[PromptArgument]] = None
) derives Codec.AsObject

/** Describes an argument that a prompt template can accept.
  */
case class PromptArgument(
    /** The name of the argument. */
    name: String,
    /** A human-readable description of the argument. */
    description: Option[String] = None,
    /** Whether this argument must be provided. */
    required: Option[Boolean] = None
) derives Codec.AsObject

/** A message in a prompt, combining a role and content.
  */
case class PromptMessage(
    role: Role,
    content: Content
) derives Codec.AsObject

/** A reference to a prompt or prompt template.
  */
case class PromptReference(
    /** The name of the prompt or prompt template. */
    name: String
) derives Codec.AsObject
