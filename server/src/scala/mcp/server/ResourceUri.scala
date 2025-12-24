package mcp.server

import io.circe.{Decoder, Encoder}

/** Type-safe wrapper for resource URIs.
  *
  * Resource URIs identify specific resources in MCP. They can be:
  *   - Static URIs: "file:///config.json", "config:///database"
  *   - Template-matched URIs: "file:///logs/2025-01-15.log" (matches template "file:///{path}")
  *
  * This type integrates with [[UriTemplate]] for template matching and variable extraction.
  */
opaque type ResourceUri = String

object ResourceUri {

  /** Create a ResourceUri from a string. */
  def apply(uri: String): ResourceUri = uri

  /** Parse and validate a resource URI.
    *
    * Currently accepts any non-empty string. Could be extended to validate:
    *   - URI syntax (scheme://path)
    *   - Allowed schemes
    *
    * @param uri
    *   The URI string to parse
    * @return
    *   Right(ResourceUri) if valid, Left(error message) if invalid
    */
  def parse(uri: String): Either[String, ResourceUri] =
    if uri.isEmpty then Left("Resource URI cannot be empty")
    else Right(uri)

  /** Unsafe creation - use when URI is known to be valid (e.g., from protocol messages). */
  def unsafeFrom(uri: String): ResourceUri = uri

  extension (uri: ResourceUri) {

    /** Get the underlying string value. */
    def value: String = uri

    /** Check if this URI matches a template pattern.
      *
      * @param template
      *   The parsed URI template to match against
      * @return
      *   true if the URI matches the template pattern
      */
    def matchesTemplate(template: UriTemplate): Boolean =
      template.matches(uri: String)

    /** Extract variable values from this URI using a template.
      *
      * @param template
      *   The parsed URI template
      * @return
      *   Some(map) with variable bindings if the URI matches, None otherwise
      */
    def extractFrom(template: UriTemplate): Option[Map[String, String]] =
      template.extractString(uri: String)
  }

  // Circe codecs for JSON serialization (protocol compatibility)
  given Encoder[ResourceUri] = Encoder.encodeString.contramap(_.value)
  given Decoder[ResourceUri] = Decoder.decodeString.map(apply)

  // For use in Sets/Maps
  given Ordering[ResourceUri] = Ordering.String.asInstanceOf[Ordering[ResourceUri]]

  // Cats instances for compatibility
  given cats.Eq[ResourceUri] = cats.Eq.fromUniversalEquals
  given cats.Show[ResourceUri] = cats.Show.show(_.value)
}
