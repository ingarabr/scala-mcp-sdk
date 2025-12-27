# Example MCP Servers

This directory contains example MCP server implementations demonstrating the scala-mcp library.

## Available Servers

### reference
Our baseline server for testing new features during development. A minimal implementation showing basic MCP features:
- Tools: echo, add, log-and-progress
- Resources: server config, timestamps
- Prompts: greeting, translate (with completions)

### mcp-everything
A Scala port of the [Everything MCP Server](https://github.com/modelcontextprotocol/servers/tree/main/src/everything) from the official MCP repository. This comprehensive test server exercises all MCP protocol features:
- Tools: echo, add, get-tiny-image, long-running-operation, sampling, get-env, annotated-message, get-roots
- Resources: static text/blob, dynamic templates
- Prompts: simple, with arguments, embedded resources

## Running with Claude Code

### Local Configuration (not committed to git)

Add the server to your local Claude Code configuration:

```bash
# Reference server
claude mcp add --transport stdio reference --scope local -- bleep run example-server-reference

# Everything server
claude mcp add --transport stdio everything --scope local -- bleep run example-server-mcp-everything
```

This stores the configuration in `.claude/settings.local.json` which is ignored by git.

### Project Configuration (shared with team)

Add to project-level configuration:

```bash
claude mcp add --transport stdio everything --scope project -- bleep run example-server-mcp-everything
```

This creates/updates `.mcp.json` in the project root, which can be committed to version control.

### Manual Configuration

Create `.mcp.json` in the project root:

```json
{
  "mcpServers": {
    "reference": {
      "command": "bleep",
      "args": ["run", "example-server-reference"]
    },
    "everything": {
      "command": "bleep",
      "args": ["run", "example-server-mcp-everything"]
    }
  }
}
```

Or for local-only (in `.claude/settings.local.json`):

```json
{
  "mcpServers": {
    "everything": {
      "command": "bleep",
      "args": ["run", "example-server-mcp-everything"]
    }
  }
}
```

## Running Standalone

You can also run the servers directly for testing:

```bash
# Reference server
bleep run example-server-reference

# Everything server  
bleep run example-server-mcp-everything
```

The servers communicate via stdin/stdout using JSON-RPC 2.0.

## Testing with MCP Inspector

You can test the servers using the [MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```bash
npx @anthropic-ai/mcp-inspector bleep run example-server-mcp-everything
```
