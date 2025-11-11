package mcp.protocol

import io.circe.*
import io.circe.syntax.*
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
      inputSchema = JsonSchemaType.ObjectSchema(),
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

  test("CompletionReference.ResourceTemplate codec roundtrip") {
    val ref = CompletionReference.ResourceTemplate("file:///path/to/{name}")
    roundupDiscriminator[CompletionReference](ref, "ref/resource")
  }

  test("CompletionReference.Prompt codec roundtrip") {
    val ref = CompletionReference.Prompt("greeting", Some("Greeting Prompt"))
    roundupDiscriminator[CompletionReference](ref, "ref/prompt")
  }

  test("Content.Text codec roundtrip with discriminator") {
    val content = Content.Text("Hello world", None, None)
    roundupDiscriminator[Content](content, "text")
  }

  test("Content.Image codec roundtrip with discriminator") {
    val content = Content.Image("base64data", "image/png", None, None)
    roundupDiscriminator[Content](content, "image")
  }

  test("Content.Audio codec roundtrip with discriminator") {
    val content = Content.Audio("base64audiodata", "audio/mpeg", None, None)
    roundupDiscriminator[Content](content, "audio")
  }

  test("Content.Resource codec roundtrip with discriminator") {
    val resourceContents = ResourceContents.Text("file:///test.txt", "content", None, None)
    val content = Content.Resource(resourceContents, None, None)
    roundupDiscriminator[Content](content, "resource")
  }

  test("Content.ResourceLink codec roundtrip with discriminator") {
    val content = Content.ResourceLink(
      uri = "file:///test.txt",
      name = "Test File",
      description = Some("A test file"),
      mimeType = Some("text/plain"),
      annotations = None,
      size = None,
      title = None,
      _meta = None
    )
    roundupDiscriminator[Content](content, "resource_link")

  }

  test("ResourceContents.Text codec roundtrip with discriminator") {
    val contents = ResourceContents.Text("file:///test.txt", "Hello", Some("text/plain"), None)
    roundupDiscriminator[ResourceContents](contents, "text")
  }

  test("ResourceContents.Blob codec roundtrip with discriminator") {
    val contents = ResourceContents.Blob("file:///image.png", "base64data", Some("image/png"), None)
    roundupDiscriminator[ResourceContents](contents, "blob")
  }

  test("JsonSchemaType codec roundtrip") {
    val schema = JsonSchemaType.ObjectSchema(
      properties = Some(
        Map(
          "name" -> JsonSchemaType.StringSchema(description = Some("The name")),
          "age" -> JsonSchemaType.IntegerSchema(description = Some("The age")),
          "active" -> JsonSchemaType.BooleanSchema(description = None)
        )
      ),
      required = Some(List("name")),
      description = Some("A person schema")
    )
    val json = schema.asJson
    val decoded = json.as[JsonSchemaType]

    assertEquals(decoded, Right(schema))

    // Also verify it's an ObjectSchema
    decoded match {
      case Right(_: JsonSchemaType.ObjectSchema) => // success
      case Right(other)                          => fail(s"Expected ObjectSchema, got: $other")
      case Left(error)                           => fail(s"Decode failed: $error")
    }

    // Verify the JSON structure
    val jsonStr = json.noSpaces
    assert(jsonStr.contains("\"type\":\"object\""))
    assert(jsonStr.contains("\"properties\""))
    assert(jsonStr.contains("\"required\""))
    assert(jsonStr.contains("\"name\""))
  }

  private def roundupDiscriminator[A: Codec](contents: A, typeValue: String): Unit = {
    val json = contents.asJson
    val decoded = json.as[A]
    assertEquals(decoded, Right(contents))
    val jsonStr = json.noSpaces
    assert(jsonStr.contains(s"\"type\":\"$typeValue\""))
  }
}
