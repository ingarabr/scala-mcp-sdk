package mcp.schema

import scala.annotation.StaticAnnotation

/** Describes case classes and their fields in generated JSON schemas.
  *
  * Use on case classes or fields. Field descriptions override class descriptions when nested.
  */
class description(val value: String) extends StaticAnnotation
