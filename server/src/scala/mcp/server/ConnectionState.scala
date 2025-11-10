package mcp.server

import mcp.protocol.ClientCapabilities

/** Connection lifecycle state machine based on MCP specification.
  *
  * The MCP specification defines three lifecycle phases:
  *
  *   1. **Initialization** - Client and server establish connection and negotiate capabilities
  *   2. **Operation** - Normal protocol communication using negotiated capabilities
  *   3. **Shutdown** - Graceful termination of the connection
  *
  * State transitions:
  * {{{
  *   Uninitialized
  *       │
  *       │ (receive initialize request)
  *       ▼
  *   Initialized(capabilities)
  *       │
  *       │ (receive initialized notification)
  *       ▼
  *   Operational(capabilities)
  *       │
  *       │ (connection closes)
  *       ▼
  *   Shutdown
  * }}}
  *
  * See: https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle
  */
enum ConnectionState {

  /** Initial state before any communication.
    *
    * In this state:
    *   - Server awaits `initialize` request from client
    *   - No operations are allowed except `initialize` and `ping`
    */
  case Uninitialized

  /** Initialization completed, capabilities negotiated.
    *
    * In this state:
    *   - Server has responded to `initialize` request
    *   - Capabilities are known and stored
    *   - Awaiting `initialized` notification from client
    *   - Limited operations allowed (mostly server cannot send requests yet)
    *
    * @param capabilities
    *   The client capabilities received during initialization
    */
  case Initialized(capabilities: ClientCapabilities)

  /** Fully operational, normal protocol communication.
    *
    * In this state:
    *   - Client has sent `initialized` notification
    *   - Both parties can use full protocol
    *   - Operations must respect negotiated capabilities
    *
    * @param capabilities
    *   The client capabilities negotiated during initialization
    */
  case Operational(capabilities: ClientCapabilities)

  /** Connection shutting down or closed.
    *
    * In this state:
    *   - Transport is closing or closed
    *   - No further operations allowed
    *   - Resources being cleaned up
    */
  case Shutdown

  /** Get client capabilities if available (in Initialized or Operational state) */
  def clientCapabilities: Option[ClientCapabilities] = this match {
    case Initialized(caps) => Some(caps)
    case Operational(caps) => Some(caps)
    case _                 => None
  }
}
