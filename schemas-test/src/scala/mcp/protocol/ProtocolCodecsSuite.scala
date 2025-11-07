package mcp.protocol

import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import munit.*

class ProtocolCodecsSuite extends FunSuite {

  test("Role codec roundtrip") {
    val role = Role.user
    val json = role.asJson
    val decoded = json.as[Role]
    assertEquals(decoded, Right(role))
  }

  test("Implementation codec roundtrip") {
    val impl = Implementation("test-server", "1.0.0")
    val json = impl.asJson
    val decoded = json.as[Implementation]
    assertEquals(decoded, Right(impl))
  }

  test("Content.Text codec roundtrip") {
    val content = Content.Text("hello world", None)
    val json = content.asJson
    val decoded = json.as[Content]
    assertEquals(decoded, Right(content))
  }

  test("Content.Image codec roundtrip") {
    val content = Content.Image("base64data", "image/png", None)
    val json = content.asJson
    val decoded = json.as[Content]
    assertEquals(decoded, Right(content))
  }

  test("InitializeRequest codec roundtrip") {
    val req = InitializeRequest(
      protocolVersion = Constants.LATEST_PROTOCOL_VERSION,
      capabilities = ClientCapabilities(),
      clientInfo = Implementation("test-client", "1.0.0")
    )
    val json = req.asJson
    val decoded = json.as[InitializeRequest]
    assertEquals(decoded, Right(req))
  }

  test("EmptyResult codec roundtrip") {
    val result = EmptyResult()
    val json = result.asJson
    val decoded = json.as[EmptyResult]
    assertEquals(decoded, Right(result))
  }

  test("Tool with annotations codec roundtrip") {
    val tool = Tool(
      name = "test-tool",
      description = Some("A test tool"),
      inputSchema = JsonObject.empty,
      annotations = Some(
        ToolAnnotations(
          title = Some("Test Tool"),
          readOnlyHint = Some(true)
        )
      )
    )
    val json = tool.asJson
    val decoded = json.as[Tool]
    assertEquals(decoded, Right(tool))
  }
}
