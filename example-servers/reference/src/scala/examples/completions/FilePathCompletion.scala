package examples.completions

import cats.effect.*
import mcp.protocol.CompletionCompletion
import mcp.server.{CompletionDef, UriTemplate}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** File path completion provider for the file resource template.
  *
  * This demonstrates variable completion for resource templates. When a client requests completions for the "file:///{path}" template, this
  * provider returns matching file and directory names.
  */
object FilePathCompletion {

  private val template: UriTemplate =
    UriTemplate.parse("file:///{path}").getOrElse(sys.error("Invalid template"))

  def apply[F[_]: Async](baseDir: Path = Paths.get(".")): CompletionDef[F] =
    CompletionDef.forResourceTemplate[F](
      uriTemplate = template,
      handler = { (varName, currentValue, _) =>
        Async[F].delay {
          if varName == "path" then {
            val completions = listPathCompletions(baseDir, currentValue)
            CompletionCompletion(
              values = completions.take(20),
              total = Some(completions.size),
              hasMore = Some(completions.size > 20)
            )
          } else {
            CompletionCompletion(values = Nil)
          }
        }
      }
    )

  private def listPathCompletions(baseDir: Path, prefix: String): List[String] = {
    val prefixPath = Paths.get(prefix)
    val (searchDir, filePrefix) =
      if prefix.endsWith("/") then (baseDir.resolve(prefix), "")
      else {
        val parent = Option(prefixPath.getParent).map(baseDir.resolve).getOrElse(baseDir)
        val name = Option(prefixPath.getFileName).map(_.toString).getOrElse("")
        (parent, name)
      }

    Try {
      if Files.isDirectory(searchDir) then {
        Files
          .list(searchDir)
          .iterator()
          .asScala
          .map { path =>
            val relativePath = baseDir.relativize(path).toString
            if Files.isDirectory(path) then relativePath + "/" else relativePath
          }
          .filter(_.toLowerCase.contains(filePrefix.toLowerCase))
          .toList
          .sorted
      } else Nil
    }.getOrElse(Nil)
  }
}
