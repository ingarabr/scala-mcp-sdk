---
sidebar_position: 3
---

# Streamable HTTP Transport

The HTTP transport exposes your MCP server as HTTP routes using the Streamable HTTP protocol. It supports multiple
concurrent clients with bidirectional streaming over HTTP.

## Design Philosophy

We provide `HttpRoutes[F]`, you provide the HTTP server. This gives you full control over:

- Server configuration (ports, TLS, etc.)
- Middleware (auth, logging, CORS)
- Deployment strategy
- Endpoint path (via http4s Router)

## Basic Usage

```scala
import cats.effect.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import com.comcast.ip4s.*
import mcp.protocol.*
import mcp.server.*
import mcp.http4s.session.*
import scala.concurrent.duration.*

type EchoInput = (message: String, upper: Option[Boolean])
given InputDef[EchoInput] = InputDef[EchoInput](
  message = InputField[String]("Message to echo"),
  upper   = InputField[Option[Boolean]]("Convert to uppercase")
)

object MyHttpServer extends IOApp.Simple {
  val echoTool = ToolDef.unstructured[IO, EchoInput](
    name = "echo",
    description = Some("Echo input")
  ) { (input, ctx) =>
    IO.pure(List(Content.Text(input.message)))
  }

  def run: IO[Unit] = {
    McpServer[IO](
      info = Implementation("my-server", "1.0.0"),
      tools = List(echoTool)
    ).use { server =>
      SessionManager[IO](
        idleTimeout = 30.minutes,
        checkInterval = 5.minutes
      ).use { sessionManager =>
        val mcpRoutes = McpHttpRoutes.routes[IO](server, sessionManager, enableSessions = true)
        val app = Router("/mcp" -> mcpRoutes).orNotFound

        EmberServerBuilder
          .default[IO]
          .withHost(host"127.0.0.1")
          .withPort(port"8080")
          .withHttpApp(app)
          .build
          .useForever
      }
    }
  }
}
```

## Endpoint Path

Routes match at Root. Use http4s `Router` to mount at your desired path:

```scala
// Endpoint at /mcp
Router("/mcp" -> mcpRoutes)

// Endpoint at /api/v1/mcp
Router("/api/v1/mcp" -> mcpRoutes)

// Multiple routes
Router(
  "/mcp" -> mcpRoutes,
  "/health" -> healthRoutes
)
```

## HTTP Methods

The transport uses these HTTP methods at the configured endpoint:

| Method   | Description                                             |
|----------|---------------------------------------------------------|
| `POST`   | Send JSON-RPC requests and receive responses            |
| `GET`    | Open streaming connection for server-to-client messages |
| `DELETE` | Terminate session                                       |

## Session Management

Each connection creates a session. Sessions are automatically cleaned up when:

- Client disconnects
- Client sends DELETE request
- Session timeout expires (configurable)

```scala
SessionManager[IO](
  idleTimeout = 30.minutes,
  checkInterval = 5.minutes
)
```

## Adding Middleware

Wrap the routes with http4s middleware:

```scala
import org.http4s.server.middleware.*

val mcpRoutes = McpHttpRoutes.routes[IO](server, sessionManager, enableSessions = true)
val withCors = CORS.policy.withAllowOriginAll(mcpRoutes)
val app = Router("/mcp" -> withCors).orNotFound
```

## Resource Subscriptions

The HTTP transport fully supports resource subscriptions:

```scala
// Client subscribes to resource updates
// When resource changes, notify via the server:
server.notifyResourceUpdated(ResourceUri("config://app/settings"))
// Client receives update notification via streaming connection
```

## CORS Configuration

For browser clients, configure CORS:

```scala
import org.http4s.server.middleware.CORS
import org.http4s.Method

val corsConfig = CORS.policy
  .withAllowOriginHost(Set("example.com"))
  .withAllowMethodsIn(Set(Method.GET, Method.POST, Method.DELETE))
  .withAllowCredentials(false)

val corsRoutes = corsConfig(mcpRoutes)
```

## Production Considerations

- **TLS**: Use a reverse proxy or configure TLS in your HTTP server
- **Authentication**: Add auth middleware before MCP routes
- **Rate limiting**: Consider rate limiting the endpoint
- **Monitoring**: Add metrics middleware for observability
