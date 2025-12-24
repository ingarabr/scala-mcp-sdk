package mcp.server

import munit.FunSuite

class UriTemplateSuite extends FunSuite {

  // ===== Parsing Tests =====

  test("parse simple variable") {
    val result = UriTemplate.parse("{var}")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.template), Right("{var}"))
    assertEquals(result.map(_.variableNames), Right(List("var")))
  }

  test("parse literal only") {
    val result = UriTemplate.parse("https://example.com/path")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.template), Right("https://example.com/path"))
    assertEquals(result.map(_.variableNames), Right(List.empty))
  }

  test("parse mixed literal and variables") {
    val result = UriTemplate.parse("file:///{path}")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.template), Right("file:///{path}"))
    assertEquals(result.map(_.variableNames), Right(List("path")))
  }

  test("parse multiple variables") {
    val result = UriTemplate.parse("{scheme}://{host}/{path}")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.variableNames), Right(List("scheme", "host", "path")))
  }

  test("parse comma-separated variables in single expression") {
    val result = UriTemplate.parse("{a,b,c}")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.variableNames), Right(List("a", "b", "c")))
    assertEquals(result.map(_.template), Right("{a,b,c}"))
  }

  test("parse variable with underscore") {
    val result = UriTemplate.parse("{my_var}")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.variableNames), Right(List("my_var")))
  }

  test("parse variable with digits") {
    val result = UriTemplate.parse("{var123}")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.variableNames), Right(List("var123")))
  }

  test("parse empty template") {
    val result = UriTemplate.parse("")
    assert(result.isRight, s"Parse failed: $result")
    assertEquals(result.map(_.template), Right(""))
  }

  test("parse fails on empty expression") {
    val result = UriTemplate.parse("{}")
    assert(result.isLeft, "Should fail on empty expression")
  }

  test("parse fails on unclosed brace") {
    val result = UriTemplate.parse("{var")
    assert(result.isLeft, "Should fail on unclosed brace")
  }

  // ===== Expansion Tests =====

  test("expand simple variable") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.expand(Map("var" -> "value")), ResourceUri("value"))
  }

  test("expand with literal prefix") {
    val template = UriTemplate.parse("file:///{path}").toOption.get
    assertEquals(template.expand(Map("path" -> "config.json")), ResourceUri("file:///config.json"))
  }

  test("expand multiple variables") {
    val template = UriTemplate.parse("{scheme}://{host}/{path}").toOption.get
    val vars = Map("scheme" -> "https", "host" -> "example.com", "path" -> "api")
    assertEquals(template.expand(vars), ResourceUri("https://example.com/api"))
  }

  test("expand undefined variable is omitted") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.expand(Map.empty), ResourceUri(""))
  }

  test("expand partial variables defined") {
    val template = UriTemplate.parse("{a}/{b}").toOption.get
    assertEquals(template.expand(Map("a" -> "first")), ResourceUri("first/"))
  }

  test("expand encodes special characters") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.expand(Map("var" -> "hello world")), ResourceUri("hello%20world"))
  }

  test("expand encodes unicode") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.expand(Map("var" -> "café")), ResourceUri("caf%C3%A9"))
  }

  test("expand preserves unreserved characters") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.expand(Map("var" -> "a-b_c.d~e")), ResourceUri("a-b_c.d~e"))
  }

  test("expand comma-separated values") {
    val template = UriTemplate.parse("{a,b}").toOption.get
    assertEquals(template.expand(Map("a" -> "1", "b" -> "2")), ResourceUri("1,2"))
  }

  // ===== Matching Tests =====

  test("matches simple variable") {
    val template = UriTemplate.parse("{var}").toOption.get
    assert(template.matches("value"))
    assert(template.matches("hello"))
    assert(template.matches(""))
  }

  test("matches with literal prefix") {
    val template = UriTemplate.parse("file:///{path}").toOption.get
    assert(template.matches("file:///config.json"))
    assert(template.matches("file:///"))
    assert(!template.matches("http:///config.json"))
    assert(!template.matches("file://config.json"))
  }

  test("matches multiple variables") {
    val template = UriTemplate.parse("config:///{section}").toOption.get
    assert(template.matches("config:///database"))
    assert(template.matches("config:///network"))
    assert(!template.matches("config://database"))
  }

  // ===== Extraction Tests =====

  test("extract simple variable") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.extractString("value"), Some(Map("var" -> "value")))
  }

  test("extract with literal prefix") {
    val template = UriTemplate.parse("file:///{path}").toOption.get
    assertEquals(template.extractString("file:///config.json"), Some(Map("path" -> "config.json")))
  }

  test("extract multiple variables") {
    val template = UriTemplate.parse("user:///{id}/profile").toOption.get
    assertEquals(template.extractString("user:///123/profile"), Some(Map("id" -> "123")))
  }

  test("extract returns None on non-match") {
    val template = UriTemplate.parse("file:///{path}").toOption.get
    assertEquals(template.extractString("http:///foo"), None)
  }

  test("extract decodes percent-encoded values") {
    val template = UriTemplate.parse("{var}").toOption.get
    assertEquals(template.extractString("hello%20world"), Some(Map("var" -> "hello world")))
  }

  test("extract empty variable value") {
    val template = UriTemplate.parse("prefix/{var}/suffix").toOption.get
    assertEquals(template.extractString("prefix//suffix"), Some(Map("var" -> "")))
  }

  // ===== Real-world MCP Template Tests =====

  test("file template pattern") {
    val template = UriTemplate.parse("file:///{path}").toOption.get

    // Expand - Note: RFC 6570 Level 1 encodes slashes. Use single path segments or encoded paths.
    assertEquals(template.expand(Map("path" -> "config.json")), ResourceUri("file:///config.json"))

    // Extract
    assertEquals(template.extractString("file:///config.json"), Some(Map("path" -> "config.json")))
  }

  test("file template with encoded slashes") {
    val template = UriTemplate.parse("file:///{path}").toOption.get

    // Slashes in values are percent-encoded per RFC 6570 Level 1 (simple string expansion)
    assertEquals(template.expand(Map("path" -> "home/user/config.json")), ResourceUri("file:///home%2Fuser%2Fconfig.json"))

    // Extract with encoded slashes
    assertEquals(template.extractString("file:///home%2Fuser%2Fconfig.json"), Some(Map("path" -> "home/user/config.json")))
  }

  test("config template pattern") {
    val template = UriTemplate.parse("config:///{section}").toOption.get

    assertEquals(template.expand(Map("section" -> "database")), ResourceUri("config:///database"))
    assertEquals(template.extractString("config:///database"), Some(Map("section" -> "database")))
  }

  test("user profile template pattern") {
    val template = UriTemplate.parse("user:///{id}/profile").toOption.get

    assertEquals(template.expand(Map("id" -> "123")), ResourceUri("user:///123/profile"))
    assertEquals(template.extractString("user:///123/profile"), Some(Map("id" -> "123")))
  }

  test("roundtrip: expand then extract") {
    val template = UriTemplate.parse("file:///{path}").toOption.get
    val vars = Map("path" -> "config.json")

    val expanded = template.expand(vars)
    val extracted = template.extract(expanded) // Uses ResourceUri overload

    assertEquals(extracted, Some(vars))
  }
}
