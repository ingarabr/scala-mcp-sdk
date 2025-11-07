package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** The role of a message sender or recipient. */
enum Role derives Codec.AsObject {
  case user, assistant
}

/** Logging levels for diagnostic messages. */
enum LoggingLevel derives Codec.AsObject {
  case debug, info, notice, warning, error, critical, alert, emergency
}

/** Optional annotations for the client. The client can use annotations to inform how objects are used or displayed.
  */
case class Annotations(
    /** Describes who the intended customer of this object or data is.
      *
      * It can include multiple entries to indicate content useful for multiple audiences (e.g., `["user", "assistant"]`).
      */
    audience: Option[List[Role]] = None,
    /** Describes how important this data is for operating the server.
      *
      * A value of 1 means "most important," and indicates that the data is effectively required, while 0 means "least important," and
      * indicates that the data is entirely optional.
      */
    priority: Option[Double] = None
) derives Codec.AsObject

/** Describes the name and version of an MCP implementation.
  */
case class Implementation(
    name: String,
    version: String
) derives Codec.AsObject
