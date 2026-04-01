package examples.tools

import cats.effect.*
import io.circe.*
import mcp.protocol.{Content, Icon, IconTheme}
import mcp.server.{InputDef, InputField, ToolDef}

object EchoTool {

  type Input = (message: String, prefix: Option[String])
  given InputDef[Input] = InputDef[Input](
    message = InputField[String]("The message to echo back"),
    prefix = InputField[Option[String]]("Optional prefix to add")
  )

  // Echo icon: arrow pointing left (symbolizing echo/return)
  // Original: <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 19l-7-7 7-7M19 12H5"/></svg>
  private val echoIconLight =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgc3Ryb2tlLXdpZHRoPSIyIj48cGF0aCBkPSJNMTIgMTlsLTctNyA3LTdNMTkgMTJINSIvPjwvc3ZnPg=="

  // Dark theme variant with white stroke
  // Original: <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><path d="M12 19l-7-7 7-7M19 12H5"/></svg>
  private val echoIconDark =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IndoaXRlIiBzdHJva2Utd2lkdGg9IjIiPjxwYXRoIGQ9Ik0xMiAxOWwtNy03IDctN00xOSAxMkg1Ii8+PC9zdmc+"

  def apply[F[_]: Async]: ToolDef[F, Input, Nothing] =
    ToolDef.unstructured[F, Input](
      name = "echo",
      description = Some("Echo back the input message"),
      icons = Some(
        List(
          Icon(src = echoIconLight, mimeType = Some("image/svg+xml"), sizes = Some(List("any")), theme = Some(IconTheme.light)),
          Icon(src = echoIconDark, mimeType = Some("image/svg+xml"), sizes = Some(List("any")), theme = Some(IconTheme.dark))
        )
      )
    ) { (input, _) =>
      val pre = input.prefix.getOrElse("Echo")
      Async[F].pure(List(Content.Text(s"$pre: ${input.message}")))
    }
}
