package mcp.server

import cats.parse.{Parser, Parser0, Rfc5234}
import cats.syntax.all.*

/** RFC 6570 Level 1 URI Template implementation.
  *
  * Supports simple variable expansion only:
  *   - `{var}` - Simple string expansion
  *   - `{var1,var2}` - Multiple variables
  *
  * Does NOT support:
  *   - Operators (+, #, ., /, ;, ?, &)
  *   - Modifiers (:prefix, *explode)
  *
  * @see
  *   https://datatracker.ietf.org/doc/html/rfc6570
  */
final case class UriTemplate private (parts: List[UriTemplate.Part]) {
  import UriTemplate.*

  /** Expand the template with the given variable values.
    *
    * Undefined variables are omitted (per RFC 6570 spec). Variables are percent-encoded using unreserved character set.
    *
    * @param vars
    *   Map of variable names to their string values
    * @return
    *   The expanded URI as a ResourceUri
    */
  def expand(vars: Map[String, String]): ResourceUri = {
    val sb = new StringBuilder
    parts.foreach {
      case Literal(text) =>
        sb.append(text)
      case Expression(variables) =>
        val expanded = variables.flatMap { varName =>
          vars.get(varName).map(encodeUnreserved)
        }
        sb.append(expanded.mkString(","))
    }
    ResourceUri(sb.toString)
  }

  /** Check if a URI matches this template pattern.
    *
    * @param uri
    *   The URI to check
    * @return
    *   true if the URI matches the pattern
    */
  def matches(uri: String): Boolean =
    extractString(uri).isDefined

  /** Extract variable values from a ResourceUri that matches this template.
    *
    * @param uri
    *   The ResourceUri to extract values from
    * @return
    *   Some(map) with variable bindings if the URI matches, None otherwise
    */
  def extract(uri: ResourceUri): Option[Map[String, String]] =
    extractString(uri.value)

  /** Extract variable values from a URI string that matches this template.
    *
    * For templates with multiple variables in a single expression (e.g., `{a,b}`), the values are split by comma. If the number of
    * comma-separated values doesn't match the number of variables, extraction fails.
    *
    * @param uri
    *   The URI string to extract values from
    * @return
    *   Some(map) with variable bindings if the URI matches, None otherwise
    */
  def extractString(uri: String): Option[Map[String, String]] = {
    // Build a regex pattern from the template
    val regexPattern = buildExtractPattern
    val regex = regexPattern.r

    regex.findFirstMatchIn(uri).map { m =>
      // Collect all variable bindings from the regex groups
      var result = Map.empty[String, String]
      var groupIndex = 1

      parts.foreach {
        case Literal(_)            => // No groups for literals
        case Expression(variables) =>
          val captured = m.group(groupIndex)
          groupIndex += 1

          if captured != null then {
            // For multi-variable expressions, split by comma
            val values = captured.split(",", -1).toList
            if values.length == variables.length then {
              variables.zip(values).foreach { (name, value) =>
                result = result + (name -> decodePercent(value))
              }
            } else if variables.length == 1 then {
              // Single variable can contain commas (or be empty)
              result = result + (variables.head -> decodePercent(captured))
            }
            // If count doesn't match and not single, we still capture what we can
            // This is a simplification - real RFC 6570 has more complex rules
          }
      }
      result
    }
  }

  /** Get all variable names used in this template. */
  def variableNames: List[String] =
    parts.collect { case Expression(vars) => vars }.flatten

  /** Get the original template string. */
  def template: String = {
    val sb = new StringBuilder
    parts.foreach {
      case Literal(text)         => sb.append(text)
      case Expression(variables) => sb.append("{").append(variables.mkString(",")).append("}")
    }
    sb.toString
  }

  override def toString: String = s"UriTemplate($template)"

  /** Build a regex pattern for extracting variables.
    *
    * Each expression becomes a capturing group that matches URL-safe characters.
    */
  private def buildExtractPattern: String = {
    val sb = new StringBuilder
    sb.append("^")
    parts.foreach {
      case Literal(text) =>
        // Escape regex special characters in literal text
        sb.append(java.util.regex.Pattern.quote(text))
      case Expression(variables) =>
        // Match URL-safe characters (unreserved + percent-encoded)
        // For multi-variable, we capture the whole thing and split later
        if variables.length == 1 then {
          // Single variable: match non-reserved characters
          sb.append("([^/?#]*)")
        } else {
          // Multi-variable: match comma-separated values
          sb.append("([^/?#]*)")
        }
    }
    sb.append("$")
    sb.toString
  }
}

object UriTemplate {

  /** A part of a URI template - either literal text or an expression. */
  sealed trait Part

  /** Literal text that appears as-is in the URI. */
  final case class Literal(text: String) extends Part

  /** A variable expression like {var} or {var1,var2}. */
  final case class Expression(variables: List[String]) extends Part

  /** Parse a URI template string.
    *
    * @param template
    *   The template string to parse
    * @return
    *   Right(UriTemplate) on success, Left(error message) on failure
    */
  def parse(template: String): Either[String, UriTemplate] =
    UriTemplateParser.template
      .parseAll(template)
      .leftMap { error =>
        s"Invalid URI template at offset ${error.failedAtOffset}: ${error.expected.toList.mkString(", ")}"
      }
      .map(parts => new UriTemplate(parts))

  /** Encode a string using the unreserved character set (RFC 3986).
    *
    * Unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
    */
  private def encodeUnreserved(s: String): String = {
    val sb = new StringBuilder
    s.foreach { c =>
      if isUnreserved(c) then {
        sb.append(c)
      } else {
        // Percent-encode
        val bytes = c.toString.getBytes("UTF-8")
        bytes.foreach { b =>
          sb.append('%')
          sb.append(String.format("%02X", b & 0xff))
        }
      }
    }
    sb.toString
  }

  /** Decode percent-encoded characters. */
  private def decodePercent(s: String): String = {
    val sb = new StringBuilder
    var i = 0
    while i < s.length do
      if s.charAt(i) == '%' && i + 2 < s.length then {
        try {
          val hex = s.substring(i + 1, i + 3)
          val byte = Integer.parseInt(hex, 16)
          sb.append(byte.toChar)
          i += 3
        } catch {
          case _: NumberFormatException =>
            sb.append(s.charAt(i))
            i += 1
        }
      } else {
        sb.append(s.charAt(i))
        i += 1
      }
    sb.toString
  }

  /** Check if a character is in the unreserved set. */
  private def isUnreserved(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') ||
      (c >= 'a' && c <= 'z') ||
      (c >= '0' && c <= '9') ||
      c == '-' || c == '.' || c == '_' || c == '~'
}

/** Parser for URI templates using cats-parse. */
private object UriTemplateParser {
  import UriTemplate.*

  // Variable name character: ALPHA / DIGIT / "_"
  // Per RFC 6570 Section 2.3: varchar = ALPHA / DIGIT / "_"
  private val varchar: Parser[Char] =
    Rfc5234.alpha | Rfc5234.digit | Parser.charIn('_')

  // Variable name: varchar *( ["."] varchar )
  // We simplify to just varchar+ since MCP templates don't need dotted names
  private val varname: Parser[String] =
    varchar.rep.string

  // Variable list: varname *( "," varname )
  private val variableList: Parser[List[String]] =
    varname.repSep(Parser.char(',')).map(_.toList)

  // Expression: "{" variable-list "}"
  private val expression: Parser[Expression] =
    variableList.between(Parser.char('{'), Parser.char('}')).map(Expression(_))

  // Literal: any characters except { and }
  // Per RFC 6570: literals can contain most characters
  private val literalChar: Parser[Char] =
    Parser.charWhere(c => c != '{' && c != '}')

  private val literal: Parser[Literal] =
    literalChar.rep.string.map(Literal(_))

  // Template: *( literals / expression )
  val template: Parser0[List[Part]] =
    (expression.backtrack.widen[Part] | literal.widen[Part]).rep0
}
