package mcp.server

import mcp.protocol.{ClientCapabilities, LoggingLevel}

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
    * @param logLevel
    *   The minimum log level for messages sent to the client. None means no filtering (send all logs).
    */
  case Initialized(capabilities: ClientCapabilities, logLevel: Option[LoggingLevel] = None)

  /** Fully operational, normal protocol communication.
    *
    * In this state:
    *   - Client has sent `initialized` notification
    *   - Both parties can use full protocol
    *   - Operations must respect negotiated capabilities
    *
    * @param capabilities
    *   The client capabilities negotiated during initialization
    * @param logLevel
    *   The minimum log level for messages sent to the client. None means no filtering (send all logs).
    */
  case Operational(capabilities: ClientCapabilities, logLevel: Option[LoggingLevel] = None)

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
    case Initialized(caps, _) => Some(caps)
    case Operational(caps, _) => Some(caps)
    case _                    => None
  }

  /** Get the minimum log level if available (in Initialized or Operational state) */
  def minLogLevel: Option[LoggingLevel] = this match {
    case Initialized(_, level) => level
    case Operational(_, level) => level
    case _                     => None
  }
}
