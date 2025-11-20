package mcp.http4s.session

import cats.effect.IO
import cats.syntax.all.*
import mcp.protocol.{Constants, Implementation, JsonRpcResponse}
import mcp.server.McpServer
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class SessionManagerTest extends CatsEffectSuite {

  /** Create a simple test server for testing. */
  private def createTestServer(): IO[McpServer[IO]] =
    McpServer[IO](
      info = Implementation(
        name = "test-server",
        version = "1.0.0"
      ),
      tools = Nil,
      resources = Nil,
      prompts = Nil
    ).allocated.map(_._1)

  /** Create a test notification message. */
  private def testNotification(): io.circe.Json = {
    import io.circe.*
    import io.circe.syntax.*
    Json.obj(
      "jsonrpc" -> Json.fromString(Constants.JSONRPC_VERSION),
      "method" -> Json.fromString("test/notification")
    )
  }

  test("createSession in session-based mode generates unique IDs") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id1 <- manager.createSession(sessionBased = true, server)
      id2 <- manager.createSession(sessionBased = true, server)
    } yield {
      assert(id1.isDefined, "First session should have ID")
      assert(id2.isDefined, "Second session should have ID")
      assert(id1 != id2, "Session IDs should be unique")
    }
  }

  test("createSession in sessionless mode returns None") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = false, server)
    } yield assert(id.isEmpty, "Sessionless mode should return None")
  }

  test("getSession returns state for existing session") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      state <- manager.getSession(id)
    } yield {
      assert(state.isDefined, "Should find session")
      assert(state.get.id == id, "Session ID should match")
      assert(state.get.capabilities.isEmpty, "Capabilities not set yet")
    }
  }

  test("getSession returns None for non-existent session") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      state <- manager.getSession(Some(SessionId.fromString("non-existent")))
    } yield assert(state.isEmpty, "Should not find non-existent session")
  }

  test("setCapabilities and getCapabilities work correctly") {
    import mcp.protocol.*

    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      caps = ClientCapabilities(
        roots = Some(RootsCapability(listChanged = Some(true))),
        sampling = None,
        experimental = None,
        elicitation = None
      )
      _ <- manager.setCapabilities(id, caps)
      retrieved <- manager.getCapabilities(id)
    } yield {
      assert(retrieved.isDefined, "Should retrieve capabilities")
      assert(retrieved.get == caps, "Capabilities should match")
    }
  }

  test("updateActivity updates lastActivity timestamp") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      state1 <- manager.getSession(id)
      _ <- IO.sleep(10.millis)
      _ <- manager.updateActivity(id)
      state2 <- manager.getSession(id)
    } yield {
      assert(state1.isDefined && state2.isDefined, "Session should exist")
      assert(
        state2.get.lastActivity.isAfter(state1.get.lastActivity),
        "Last activity should be updated"
      )
    }
  }

  test("appendEvent assigns sequential event IDs") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      msg1 = testNotification()
      msg2 = testNotification()
      eventId1 <- manager.appendEvent(id, msg1)
      eventId2 <- manager.appendEvent(id, msg2)
    } yield {
      assert(eventId1.value == 0L, "First event should be ID 0")
      assert(eventId2.value == 1L, "Second event should be ID 1")
    }
  }

  test("getEventsSince returns events after given ID") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      msg1 = testNotification()
      msg2 = testNotification()
      msg3 = testNotification()
      eventId1 <- manager.appendEvent(id, msg1)
      eventId2 <- manager.appendEvent(id, msg2)
      eventId3 <- manager.appendEvent(id, msg3)
      events <- manager.getEventsSince(id, eventId1)
    } yield {
      assert(events.size == 2, "Should return 2 events after ID 0")
      assert(events.head._1 == eventId2, "First event should be ID 1")
      assert(events(1)._1 == eventId3, "Second event should be ID 2")
    }
  }

  test("event log is bounded by eventLogSize") {
    for {
      manager <- SessionManager.withoutCleanup[IO](eventLogSize = 3)
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      _ <- (1 to 5).toList.traverse { _ =>
        manager.appendEvent(id, testNotification())
      }
      state <- manager.getSession(id)
    } yield {
      assert(state.isDefined, "Session should exist")
      assert(state.get.eventLog.size == 3, "Event log should be bounded to 3")
      assert(state.get.eventLog.head._1.value == 2L, "Oldest event should be ID 2")
      assert(state.get.eventLog.last._1.value == 4L, "Newest event should be ID 4")
    }
  }

  test("removeSession cleans up session") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      _ <- manager.removeSession(id)
      state <- manager.getSession(id)
    } yield assert(state.isEmpty, "Session should be removed")
  }

  test("enqueuePostResponse and enqueuePersistent add messages to queues") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = true, server)
      state <- manager.getSession(id)
      jsonMsg = testNotification()
      responseMsg = JsonRpcResponse.Notification(
        jsonrpc = Constants.JSONRPC_VERSION,
        method = "test/notification",
        params = None
      )
      _ <- manager.enqueuePostResponse(id, responseMsg)
      _ <- manager.enqueuePersistent(id, jsonMsg)
      postMsg <- state.get.postResponseQueue.take
      persistentMsg <- state.get.persistentQueue.take
    } yield {
      assert(postMsg.isDefined, "Post queue should have message")
      assert(persistentMsg.isDefined, "Persistent queue should have message")
    }
  }

  test("concurrent sessions are isolated") {
    import io.circe.*

    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id1 <- manager.createSession(sessionBased = true, server)
      id2 <- manager.createSession(sessionBased = true, server)
      caps1 = mcp.protocol.ClientCapabilities(None, None, None, None)
      caps2 = mcp.protocol.ClientCapabilities(
        sampling = Some(JsonObject("enabled" -> Json.fromBoolean(true))),
        roots = None,
        experimental = None,
        elicitation = None
      )
      _ <- manager.setCapabilities(id1, caps1)
      _ <- manager.setCapabilities(id2, caps2)
      retrieved1 <- manager.getCapabilities(id1)
      retrieved2 <- manager.getCapabilities(id2)
    } yield {
      assert(retrieved1.get == caps1, "Session 1 capabilities should be isolated")
      assert(retrieved2.get == caps2, "Session 2 capabilities should be isolated")
      assert(retrieved1 != retrieved2, "Sessions should have different capabilities")
    }
  }

  test("sessionless mode (None ID) works correctly") {
    for {
      manager <- SessionManager.withoutCleanup[IO]()
      server <- createTestServer()
      id <- manager.createSession(sessionBased = false, server)
      state1 <- manager.getSession(None)
      caps = mcp.protocol.ClientCapabilities(None, None, None, None)
      _ <- manager.setCapabilities(None, caps)
      state2 <- manager.getSession(None)
    } yield {
      assert(id.isEmpty, "Sessionless should return None")
      assert(state1.isDefined, "Should find sessionless state")
      assert(state2.get.capabilities.isDefined, "Capabilities should be set")
    }
  }
}
