---
sidebar_position: 4
---

# Coming from TypeScript / Python

If you've built MCP servers with the [TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk) or [Python SDK](https://github.com/modelcontextprotocol/python-sdk), this page maps familiar patterns to their Scala equivalents.

For Scala syntax basics, see [Key Concepts](./concepts.md).

## Defining a Tool

### TypeScript

```typescript
server.tool(
  "greet",
  "Greet someone",
  {
    name: z.string().describe("Name to greet"),
    excited: z.boolean().optional().describe("Add exclamation marks")
  },
  async ({ name, excited }) => ({
    content: [{ type: "text", text: `Hello, ${name}${excited ? "!!!" : "!"}` }]
  })
);
```

### Python

```python
from pydantic import Field

@server.tool("greet", "Greet someone")
async def greet(
    name: str = Field(description="Name to greet"),
    excited: bool = Field(default=False, description="Add exclamation marks")
) -> list[TextContent]:
    mark = "!!!" if excited else "!"
    return [TextContent(type="text", text=f"Hello, {name}{mark}")]
```

### Scala

```scala
type GreetInput = (name: String, excited: Option[Boolean])
given InputDef[GreetInput] = InputDef[GreetInput](
  name    = InputField[String]("Name to greet"),
  excited = InputField[Option[Boolean]]("Add exclamation marks")
)

val greetTool = ToolDef.unstructured[IO, GreetInput](
  name = "greet",
  description = Some("Greet someone")
) { (input, ctx) =>
  val mark = if input.excited.getOrElse(false) then "!!!" else "!"
  IO.pure(List(Content.Text(s"Hello, ${input.name}$mark")))
}
```

## Input Schema

| TypeScript (Zod) | Python | Scala |
|---|---|---|
| `z.string().describe("...")` | `str = Field(description="...")` | `InputField[String]("...")` |
| `z.number().describe("...")` | `float = Field(description="...")` | `InputField[Double]("...")` |
| `z.boolean().describe("...")` | `bool = Field(description="...")` | `InputField[Boolean]("...")` |
| `z.string().optional()` | `str \| None = None` | `InputField[Option[String]]("...")` |
| `z.object({...})` | Pydantic `BaseModel` | `InputDef[MyType](...)` |

All three SDKs generate JSON Schema from these definitions. The key difference in Scala: the schema definition (`InputDef`) is separate from the type definition (named tuple or case class). The `given` keyword makes it available to `ToolDef` automatically — you don't pass it explicitly.

## Server Setup

### TypeScript

```typescript
const server = new McpServer({ name: "my-server", version: "1.0.0" });
server.tool("greet", ...);

const transport = new StdioServerTransport();
await server.connect(transport);
```

### Python

```python
server = Server("my-server")

@server.tool()
async def greet(...): ...

async with stdio_server() as (read, write):
    await server.run(read, write, InitializationOptions(...))
```

### Scala

```scala
object MyServer extends IOApp.Simple {
  def run: IO[Unit] =
    (for {
      server    <- McpServer[IO](
        info = Implementation("my-server", "1.0.0"),
        tools = List(greetTool)
      )
      transport <- StdioTransport[IO]()
      _         <- server.serve(transport)
    } yield ()).useForever
}
```

The main structural difference: in Scala, tools are passed to the server at creation rather than registered via method calls. The `for`/`yield` block sequences the setup, and `Resource` handles cleanup automatically.

## Key Differences

| Concept             | TypeScript / Python            | Scala  T                           |
|---------------------|--------------------------------|------------------------------------|
| Async model         | `async`/`await`                | `IO[A]` with `for`/`yield`         |
| Schema definition   | Zod / type hints               | `InputDef` + `InputField`          |
| Schema wiring       | Inline argument                | `given`/`using` (implicit)         |
| Tool registration   | `server.tool(...)`             | Passed to `McpServer(tools = ...)` |
| Optional fields     | `.optional()` / `None` default | `Option[A]`                        |
| Return type         | `{ content: [...] }`           | `IO[List[Content]]`                |
| Cleanup / lifecycle | Manual / context managers      | `Resource` (automatic)             |
