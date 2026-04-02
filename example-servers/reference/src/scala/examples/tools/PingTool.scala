package examples.tools

import cats.effect.*
import mcp.protocol.Content
import mcp.server.ToolDef

object PingTool {

  def apply[F[_]: Async]: ToolDef[F, Unit, Nothing] =
    ToolDef.unstructured[F, Unit](
      name = "ping",
      description = Some("Ping the server to check if it's alive")
    ) { (_, _) =>
      Async[F].pure(List(Content.Text("pong")))
    }
}
