package mcp.server

import mcp.protocol.Content

/** Output from a tool execution, either unstructured content or structured data. */
sealed trait ToolOutput[+A]

object ToolOutput {
  case class Unstructured(content: List[Content]) extends ToolOutput[Nothing]
  case class Structured[A](data: A) extends ToolOutput[A]

}
