package examples.resources

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import mcp.protocol.{Icon, LoggingLevel}
import mcp.server.{ResourceContext, ResourceDef, ResourceTemplateDef}

/** File resource template demonstrating dynamic parameterized resources.
  *
  * This uses in-memory dummy data to demonstrate:
  *   - URI template pattern matching (file:///{path})
  *   - ResourceContext for logging
  *   - Roots validation concept
  *   - Dynamic resource resolution
  */
object FileTemplateResource {

  /** In-memory "file system" with dummy data */
  private val files: Map[String, FileData] = Map(
    "readme.md" -> FileData(
      content = """# Example Project
                  |
                  |This is a sample project demonstrating MCP resources.
                  |
                  |## Getting Started
                  |
                  |Run `sbt compile` to build the project.
                  |""".stripMargin,
      mimeType = "text/markdown",
      size = 156
    ),
    "config.json" -> FileData(
      content = """{
                  |  "name": "my-app",
                  |  "version": "1.0.0",
                  |  "database": {
                  |    "host": "localhost",
                  |    "port": 5432
                  |  }
                  |}""".stripMargin,
      mimeType = "application/json",
      size = 112
    ),
    "src/main.scala" -> FileData(
      content = """package example
                  |
                  |object Main extends App {
                  |  println("Hello, MCP!")
                  |}
                  |""".stripMargin,
      mimeType = "text/x-scala",
      size = 78
    ),
    "notes.txt" -> FileData(
      content = """TODO:
                  |- Implement feature X
                  |- Fix bug in module Y
                  |- Update documentation
                  |""".stripMargin,
      mimeType = "text/plain",
      size = 72
    )
  )

  /** Metadata about a file */
  private case class FileData(
      content: String,
      mimeType: String,
      size: Long
  )

  // File/document icon
  private val fileIcon =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgc3Ryb2tlLXdpZHRoPSIyIj48cGF0aCBkPSJNMTQgMkg2YTIgMiAwIDAgMC0yIDJ2MTZhMiAyIDAgMCAwIDIgMmgxMmEyIDIgMCAwIDAgMi0yVjhsLTYtNnoiLz48cG9seWxpbmUgcG9pbnRzPSIxNCAyIDE0IDggMjAgOCIvPjwvc3ZnPg=="

  /** Create the file template resource.
    *
    * Pattern: file:///{path}
    *
    * Examples:
    *   - file:///readme.md
    *   - file:///config.json
    *   - file:///src/main.scala
    */
  def apply[F[_]: Async]: ResourceTemplateDef[F] =
    ResourceTemplateDef[F](
      uriTemplate = "file:///{path}",
      name = "Workspace Files",
      title = Some("Project Files"),
      description = Some("Read files from the workspace. Available files: readme.md, config.json, src/main.scala, notes.txt"),
      mimeType = Some("text/plain"),
      icons = Some(
        List(
          Icon(src = fileIcon, mimeType = Some("image/svg+xml"), sizes = Some(List("any")))
        )
      ),
      resolver = { (params, ctx) =>
        val path = params.getOrElse("path", "")

        // Log the access attempt
        ctx
          .log(
            LoggingLevel.info,
            Json.obj(
              "event" -> "file_access".asJson,
              "path" -> path.asJson
            ),
            Some("FileTemplateResource")
          )
          .flatMap { _ =>
            // Check if path is in our "allowed" roots (demonstration only)
            val isAllowed = ctx.roots match {
              case Some(roots) =>
                // In a real implementation, we'd validate path is within roots
                // For demo, we just log what roots are available
                ctx.log(
                  LoggingLevel.debug,
                  Json.obj(
                    "message" -> "Checking roots".asJson,
                    "roots" -> roots.map(_.uri).asJson,
                    "path" -> path.asJson
                  ),
                  Some("FileTemplateResource")
                )
              case None =>
                ctx.log(
                  LoggingLevel.debug,
                  Json.obj("message" -> "No roots configured, allowing access".asJson),
                  Some("FileTemplateResource")
                )
            }

            isAllowed.flatMap { _ =>
              // Look up the file in our in-memory "file system"
              files.get(path) match {
                case Some(fileData) =>
                  ctx
                    .log(
                      LoggingLevel.debug,
                      Json.obj(
                        "event" -> "file_found".asJson,
                        "path" -> path.asJson,
                        "size" -> fileData.size.asJson
                      ),
                      Some("FileTemplateResource")
                    )
                    .as(
                      Some(
                        ResourceDef[F, String](
                          uri = s"file:///$path",
                          name = path,
                          title = Some(path.split("/").lastOption.getOrElse(path)),
                          description = Some(s"File contents of $path"),
                          mimeType = Some(fileData.mimeType),
                          size = Some(fileData.size),
                          handler = _ => Async[F].pure(Some(fileData.content))
                        )(using Encoder.encodeString)
                      )
                    )

                case None =>
                  ctx
                    .log(
                      LoggingLevel.warning,
                      Json.obj(
                        "event" -> "file_not_found".asJson,
                        "path" -> path.asJson,
                        "available" -> files.keys.toList.asJson
                      ),
                      Some("FileTemplateResource")
                    )
                    .as(None)
              }
            }
          }
      }
    )

  /** List all available file paths (for documentation/discovery). */
  def availableFiles: List[String] = files.keys.toList.sorted
}
