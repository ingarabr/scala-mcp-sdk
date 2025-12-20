package mcp.protocol

import io.circe.*
import io.circe.generic.semiauto.*

/** The role of a message sender or recipient. */
enum Role derives EnumCodec {
  case user, assistant
}

/** Logging levels for diagnostic messages. */
enum LoggingLevel derives EnumCodec {
  case debug, info, notice, warning, error, critical, alert, emergency
}

/** Theme specifier for icons. */
enum IconTheme derives EnumCodec {
  case dark, light
}

/** An optionally-sized icon that can be displayed in a user interface. */
case class Icon(
    /** A standard URI pointing to an icon resource. */
    src: String,
    /** Optional MIME type override if the source MIME type is missing or generic. */
    mimeType: Option[String] = None,
    /** Optional array of strings that specify sizes at which the icon can be used. */
    sizes: Option[List[String]] = None,
    /** Optional specifier for the theme this icon is designed for. */
    theme: Option[IconTheme] = None
) derives Codec.AsObject

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
    priority: Option[Double] = None,
    /** The moment the resource was last modified, as an ISO 8601 formatted string.
      *
      * Should be an ISO 8601 formatted string (e.g., "2025-01-12T15:00:58Z").
      */
    lastModified: Option[String] = None
) derives Codec.AsObject

/** Describes the name and version of an MCP implementation.
  */
case class Implementation(
    name: String,
    version: String,
    /** An optional title for display purposes. If not provided, the name should be used for display. */
    title: Option[String] = None,
    /** An optional human-readable description of what this implementation does. */
    description: Option[String] = None
) derives Codec.AsObject
