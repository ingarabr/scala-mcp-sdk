# MCP Streamable HTTP Transport - Complete Flow Breakdown

This document breaks down the Model Context Protocol (MCP) Streamable HTTP transport with concrete HTTP examples using JetBrains HTTP syntax.

## Overview

**Key Concepts:**
- **One endpoint, two methods**: POST (client → server), GET (server → client)
- **Session-based**: Server assigns session ID during initialization
- **Bidirectional**: Client sends via POST, receives via GET (SSE stream)
- **Response format**: POST can return either JSON or SSE depending on message type

---

## Sequence 1: Initialization

### Step 1.1: Client sends initialize request

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {
      "roots": {
        "listChanged": true
      }
    },
    "clientInfo": {
      "name": "example-client",
      "version": "1.0.0"
    }
  }
}
```

**What happens:**
1. Client POSTs initialize request to `/mcp`
2. Includes `Accept` header with both formats (required)
3. Request body is standard JSON-RPC request

READ:
```
HTTP/1.1 POST /mcp 
Host: localhost:8080
User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:144.0) Gecko/20100101 Firefox/144.0
Accept: application/json, text/event-stream, 
Accept-Language: en-GB,en;q=0.5
Accept-Encoding: gzip, deflate, br, zstd
Referer: http://localhost:6274/
content-type: application/json
Content-Length: 225
Origin: http://localhost:6274
Connection: keep-alive
Sec-Fetch-Dest: empty
Sec-Fetch-Mode: cors
Sec-Fetch-Site: same-site, Priority: u=0) 

{
  "method":"initialize",
  "params":{
    "protocolVersion":"2025-11-25",
    "capabilities":{"sampling":{},"elicitation":{},"roots":{"listChanged":true}},
    "clientInfo":{
      "name":"inspector-client",
      "version":"0.17.2"
    }
  },
  "jsonrpc":"2.0",
  "id":0
}"
```

### Step 1.2: Server responds with session ID

```http
HTTP/1.1 200 OK
Content-Type: application/json
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
Cache-Control: no-cache
Connection: keep-alive

{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-11-25",
    "capabilities": {
      "tools": {},
      "resources": {},
      "prompts": {}
    },
    "serverInfo": {
      "name": "example-server",
      "version": "1.0.0"
    }
  }
}
```

**What happens:**
1. Server responds with `Content-Type: application/json` (single response)
2. **Critical**: Server includes `Mcp-Session-Id` header with unique session ID
3. Response body is standard JSON-RPC response with `InitializeResult`
4. Client must save this session ID for all future requests

REAL:
```
HTTP/1.1 200 OK 
Transfer-Encoding: chunked
Content-Type: application/json
Cache-Control: no-cache
Connection: keep-alive
Mcp-Session-Id: 864afdef-bec2-4d23-88d3-7a604dc84ed5
Access-Control-Allow-Origin: *
{
  "jsonrpc":"2.0",
  "id":0,
  "result": {
    "protocolVersion":"2025-11-25",
    "capabilities":{
      "experimental":null,
      "logging":null,
      "completions":null,
      "prompts":{"listChanged":false},
      "resources":{"subscribe":false, "listChanged":false },
      "tools":{"listChanged":false}
    },
    "serverInfo":{
      "name":"http-mcp-server",
      "version":"1.0.0",
      "title":null
    },
    "instructions":null,
    "_meta":null
  }
}
```

---

## Sequence 2: Acknowledgment

### Step 2.1: Client sends initialized notification

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25

{
  "jsonrpc": "2.0",
  "method": "notifications/initialized"
}
```

**What happens:**
1. Client POSTs initialized notification (no `id` field - it's a notification)
2. **Must include** `Mcp-Session-Id` header from step 1.2
3. **Must include** `MCP-Protocol-Version` header
4. Request body has no `params` field (notifications can omit it)

### Step 2.2: Server acknowledges

```http
HTTP/1.1 202 Accepted
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

```

**What happens:**
1. Server returns HTTP 202 Accepted (no body)
2. For notifications/responses, server DOES NOT send JSON-RPC response
3. Empty body indicates acknowledgment

---

## Sequence 3: Client Request (Single Response)

### Step 3.1: Client calls a tool

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25

{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "echo",
    "arguments": {
      "message": "Hello, World!"
    }
  }
}
```

### Step 3.2: Server responds with result

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-cache
Connection: keep-alive

{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Echo: Hello, World!"
      }
    ]
  }
}
```

**What happens:**
1. Server chooses to respond with single JSON response (`Content-Type: application/json`)
2. Response contains the tool call result
3. Connection closes after response sent

---

## Sequence 4: Client Request (SSE Stream Response)

### Step 4.1: Client calls a tool (same as before)

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25

{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "longRunningTask",
    "arguments": {}
  }
}
```

### Step 4.2: Server responds with SSE stream

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

id: 1
event: message
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"task-1","progress":25,"total":100}}

id: 2
event: message
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"task-1","progress":50,"total":100}}

id: 3
event: message
data: {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Task completed!"}]}}

```

**What happens:**
1. Server chooses SSE stream format (`Content-Type: text/event-stream`)
2. Server can send progress notifications before the final response
3. Each SSE event has:
   - `id: N` - Unique event ID (for resumability)
   - `event: message` - Event type (always "message" for MCP)
   - `data: {...}` - JSON-RPC message (notification or response)
   - Empty line separates events
4. Stream closes after final response (`id: 3`)

---

## Sequence 5: Server-Initiated Messages (GET Stream)

### Step 5.1: Client opens persistent GET stream

```http
GET http://localhost:8080/mcp
Accept: text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25
```

**What happens:**
1. Client opens GET request for persistent SSE stream
2. `Accept: text/event-stream` required
3. Includes session ID and protocol version

### Step 5.2: Server keeps stream open, sends messages

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

id: 100
event: message
data: {"jsonrpc":"2.0","method":"notifications/resources/list_changed"}

id: 101
event: message
data: {"jsonrpc":"2.0","id":"server-req-1","method":"resources/list","params":{}}

```

**What happens:**
1. Stream stays open indefinitely
2. Server sends unsolicited notifications (like `resources/list_changed`)
3. Server can send requests to client (like `resources/list`)
4. Client must respond to server requests via separate POST

### Step 5.3: Client responds to server request

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25

{
  "jsonrpc": "2.0",
  "id": "server-req-1",
  "result": {
    "resources": []
  }
}
```

```http
HTTP/1.1 202 Accepted
Content-Type: text/event-stream
```

**What happens:**
1. Client POSTs response (not a request, just a response to server's request)
2. Uses same `id` that server sent (`"server-req-1"`)
3. Server acknowledges with 202 Accepted

---

## Sequence 6: Reconnection with Last-Event-ID

### Step 6.1: Client reconnects after network issue

```http
GET http://localhost:8080/mcp
Accept: text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25
Last-Event-ID: 101
```

**What happens:**
1. Client reconnects with `Last-Event-ID` header
2. Header contains the last event ID client received (`101`)
3. Server should replay any events after ID 101

### Step 6.2: Server replays missed events

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

id: 102
event: message
data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}

id: 103
event: message
data: {"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","logger":"server","data":"Ready"}}

```

**What happens:**
1. Server replays events 102, 103 (events after 101)
2. Then continues with live stream
3. Client receives missed messages without data loss

---

## Sequence 7: Session Termination

### Step 7.1: Client terminates session

```http
DELETE http://localhost:8080/mcp
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25
```

```http
HTTP/1.1 200 OK
```

**What happens:**
1. Client sends DELETE request to terminate session
2. Server cleans up session resources
3. Server may return 405 Method Not Allowed if DELETE not supported

### Step 7.2: Server terminates session (expired)

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2025-11-25

{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/list"
}
```

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 4,
  "error": {
    "code": -32603,
    "message": "Session expired"
  }
}
```

**What happens:**
1. Client sends request with old session ID
2. Server responds with 404 Not Found (session gone)
3. Client must re-initialize with new `initialize` request

---

## Error Handling

### Missing Session ID

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2025-11-25

{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/list"
}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 5,
  "error": {
    "code": -32600,
    "message": "Missing Mcp-Session-Id header"
  }
}
```

### Wrong Protocol Version

```http
POST http://localhost:8080/mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 550e8400-e29b-41d4-a716-446655440000
MCP-Protocol-Version: 2024-11-05

{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/list"
}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 6,
  "error": {
    "code": -32600,
    "message": "Unsupported protocol version: 2024-11-05"
  }
}
```

---

## Quick Reference

### POST Request Rules

| Message Type | Server Response | Content-Type | Body |
|--------------|----------------|--------------|------|
| **Request** (has `id`) | Single response | `application/json` | Single JSON-RPC response |
| **Request** (has `id`) | Multi-message | `text/event-stream` | SSE stream ending with response |
| **Notification** (no `id`) | Acknowledgment | `text/event-stream` | Empty (HTTP 202) |
| **Response** (to server request) | Acknowledgment | `text/event-stream` | Empty (HTTP 202) |

### Required Headers

| Header | When | Value |
|--------|------|-------|
| `Content-Type` | All POST | `application/json` |
| `Accept` | All POST | `application/json, text/event-stream` |
| `Accept` | All GET | `text/event-stream` |
| `Mcp-Session-Id` | After init | Session ID from server |
| `MCP-Protocol-Version` | After init | Negotiated version (e.g., `2025-11-25`) |
| `Last-Event-ID` | GET reconnect | Last received event ID |

### HTTP Status Codes

| Code | Meaning |
|------|---------|
| `200 OK` | Successful request with body |
| `202 Accepted` | Notification/response acknowledged |
| `400 Bad Request` | Missing header or invalid request |
| `404 Not Found` | Session expired/not found |
| `405 Method Not Allowed` | Endpoint doesn't support method (e.g., DELETE) |

---

## Common Patterns

### Pattern 1: Simple Request-Response
```
Client POST (request) → Server 200 OK (JSON response)
```

### Pattern 2: Streaming Request-Response
```
Client POST (request) → Server 200 OK (SSE stream with progress + final response)
```

### Pattern 3: Fire-and-Forget Notification
```
Client POST (notification) → Server 202 Accepted (empty)
```

### Pattern 4: Server-Initiated Request
```
Server sends request via GET stream → Client POST (response) → Server 202 Accepted
```

### Pattern 5: Bidirectional Communication
```
Client: Maintains open GET stream for server messages
Client: Sends POST for each outgoing message
Server: Responds via POST response or GET stream
```

---

## Key Insights

1. **POST is for client → server**: Every message from client requires a new POST
2. **GET is for server → client**: Persistent SSE stream for unsolicited server messages
3. **Session ID is critical**: Must be included in ALL requests after initialization
4. **Accept header is required**: Client must advertise both JSON and SSE support
5. **Response format varies**: Server chooses JSON (single) or SSE (multi-message) based on needs
6. **Event IDs enable resumability**: GET stream can be reconnected with `Last-Event-ID`
7. **Notifications get 202**: No JSON-RPC response body, just HTTP acknowledgment
8. **Responses to server requests**: Client POSTs JSON-RPC response object, gets 202 back

---

## Testing Your Implementation

Use these test cases to verify correct behavior:

1. ✅ Initialize without session ID → Returns session ID in header
2. ✅ Request with valid session ID → Success
3. ✅ Request without session ID (after init) → 400 Bad Request
4. ✅ Request with expired session ID → 404 Not Found
5. ✅ Request with wrong protocol version → 400 Bad Request
6. ✅ Notification → 202 Accepted (no body)
7. ✅ GET stream stays open → Receives server messages
8. ✅ GET with Last-Event-ID → Replays missed events
9. ✅ DELETE session → Cleanup successful
10. ✅ POST request → Can return either JSON or SSE
