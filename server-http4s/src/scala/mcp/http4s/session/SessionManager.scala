package mcp.http4s.session

import cats.effect.{Async, Ref}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import mcp.protocol.{ClientCapabilities, JsonRpcRequest, JsonRpcResponse}
import mcp.server.McpServer

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Manages MCP session lifecycle and state.
  *
  * Supports both:
  *   - Sessionless mode: Single implicit session (like stdio transport)
  *   - Session-based mode: Multiple concurrent sessions with IDs
  */
trait SessionManager[F[_]] {

  /** Create a new session.
    *
    * @param sessionBased
    *   If true, assigns a unique session ID. If false, creates sessionless (None).
    * @param server
    *   MCP server instance to run in background for this session
    * @return
    *   Session ID if session-based, None if sessionless
    */
  def createSession(sessionBased: Boolean, server: McpServer[F]): F[Option[SessionId]]

  /** Get session state by ID.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @return
    *   Session state if exists
    */
  def getSession(id: Option[SessionId]): F[Option[SessionState[F]]]

  /** Update session activity timestamp.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    */
  def updateActivity(id: Option[SessionId]): F[Unit]

  /** Remove session and clean up resources.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    */
  def removeSession(id: Option[SessionId]): F[Unit]

  /** Set client capabilities for session.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @param caps
    *   Client capabilities from initialize request
    */
  def setCapabilities(id: Option[SessionId], caps: ClientCapabilities): F[Unit]

  /** Get client capabilities for session.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @return
    *   Capabilities if session initialized
    */
  def getCapabilities(id: Option[SessionId]): F[Option[ClientCapabilities]]

  /** Append event to session log and return assigned event ID.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @param msg
    *   Server→client message to log
    * @return
    *   Event ID assigned to this message
    */
  def appendEvent(id: Option[SessionId], msg: JsonRpcResponse): F[EventId]

  /** Get events since given event ID (for SSE reconnection).
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @param eventId
    *   Last event ID client received
    * @return
    *   All events after eventId
    */
  def getEventsSince(id: Option[SessionId], eventId: EventId): F[Vector[(EventId, JsonRpcResponse)]]

  /** Enqueue message for POST response stream.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @param msg
    *   Response or Error to send in reply to client request
    */
  def enqueuePostResponse(id: Option[SessionId], msg: JsonRpcResponse): F[Unit]

  /** Enqueue message for persistent GET stream.
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @param msg
    *   Server→client message
    */
  def enqueuePersistent(id: Option[SessionId], msg: JsonRpcResponse): F[Unit]

  /** Enqueue request from client (POST /mcp).
    *
    * @param id
    *   Session ID (None for sessionless mode)
    * @param request
    *   JSON-RPC request from client
    */
  def enqueueRequest(id: Option[SessionId], request: JsonRpcRequest): F[Unit]

}

object SessionManager {

  /** Create a new session manager without automatic cleanup.
    *
    * Useful for testing or when manual session management is preferred.
    *
    * @param eventLogSize
    *   Maximum events to keep for reconnection (default: 1000)
    * @param queueSize
    *   Size of message queues per session (default: 100)
    */
  def withoutCleanup[F[_]: Async](
      eventLogSize: Int = 1000,
      queueSize: Int = 100
  ): F[SessionManager[F]] =
    Ref.of[F, Map[Option[SessionId], SessionState[F]]](Map.empty).map { sessionsRef =>
      new HttpSessionManager[F](sessionsRef, eventLogSize, queueSize)
    }

  /** Create a new session manager as a Resource with background cleanup.
    *
    * The cleanup fiber is started automatically and cancelled when the Resource is released.
    *
    * @param idleTimeout
    *   Remove sessions idle longer than this
    * @param checkInterval
    *   How often to check for idle sessions
    * @param eventLogSize
    *   Maximum events to keep for reconnection (default: 1000)
    * @param queueSize
    *   Size of message queues per session (default: 100)
    */
  def apply[F[_]: Async](
      idleTimeout: FiniteDuration,
      checkInterval: FiniteDuration,
      eventLogSize: Int = 1000,
      queueSize: Int = 100
  ): cats.effect.Resource[F, SessionManager[F]] =
    cats.effect.Resource
      .make {
        for {
          sessionsRef <- Ref.of[F, Map[Option[SessionId], SessionState[F]]](Map.empty)
          manager = new HttpSessionManager[F](sessionsRef, eventLogSize, queueSize)
          cleanupFiber <- startCleanupFiber(manager, idleTimeout, checkInterval)
        } yield (manager, cleanupFiber)
      } { case (_, fiber) =>
        fiber.cancel
      }
      .map(_._1)

  /** Start background cleanup fiber.
    *
    * @param manager
    *   Session manager instance
    * @param idleTimeout
    *   Remove sessions idle longer than this
    * @param checkInterval
    *   How often to check for idle sessions
    */
  private def startCleanupFiber[F[_]: Async](
      manager: SessionManager[F],
      idleTimeout: FiniteDuration,
      checkInterval: FiniteDuration
  ): F[cats.effect.Fiber[F, Throwable, Unit]] =
    Stream
      .fixedDelay[F](checkInterval)
      .evalMap { _ =>
        val now = Instant.now()
        manager match {
          case impl: HttpSessionManager[F] =>
            impl.sessionsRef.get.flatMap { sessions =>
              sessions.toList.traverse_ { case (sessionId, state) =>
                val idleDuration = java.time.Duration.between(state.lastActivity, now)
                if idleDuration.toMillis > idleTimeout.toMillis
                then manager.removeSession(sessionId)
                else Async[F].unit
              }
            }
          case _ => Async[F].unit
        }
      }
      .compile
      .drain
      .start
}

/** HTTP session manager implementation. */
private[session] class HttpSessionManager[F[_]: Async](
    private[session] val sessionsRef: Ref[F, Map[Option[SessionId], SessionState[F]]],
    eventLogSize: Int,
    queueSize: Int
) extends SessionManager[F] {

  def createSession(sessionBased: Boolean, server: McpServer[F]): F[Option[SessionId]] =
    for {
      sessionIdOpt <- if sessionBased then SessionId.generate[F]().map(Some(_)) else Async[F].pure(None)
      postQueue <- Queue.bounded[F, Option[JsonRpcResponse]](queueSize)
      persistentQueue <- Queue.bounded[F, Option[JsonRpcResponse]](queueSize)
      requestQueue <- Queue.bounded[F, Option[JsonRpcRequest]](queueSize)

      transport = new HttpSessionTransport[F](sessionIdOpt, this, requestQueue)

      serverFiber <- server.serve(transport).start

      now = Instant.now()
      state = SessionState[F](
        id = sessionIdOpt,
        capabilities = None,
        createdAt = now,
        lastActivity = now,
        eventLog = Vector.empty,
        nextEventId = EventId.zero,
        postResponseQueue = postQueue,
        persistentQueue = persistentQueue,
        requestQueue = requestQueue,
        transport = transport,
        serverFiber = serverFiber
      )
      _ <- sessionsRef.update(_ + (sessionIdOpt -> state))
    } yield sessionIdOpt

  def getSession(id: Option[SessionId]): F[Option[SessionState[F]]] =
    sessionsRef.get.map(_.get(id))

  def updateActivity(id: Option[SessionId]): F[Unit] =
    sessionsRef.update { sessions =>
      sessions.get(id) match {
        case Some(state) =>
          sessions + (id -> state.copy(lastActivity = Instant.now()))
        case None =>
          sessions
      }
    }

  def removeSession(id: Option[SessionId]): F[Unit] =
    for {
      sessionOpt <- getSession(id)
      _ <- sessionOpt match {
        case Some(session) =>
          // Cancel server fiber, terminate queues, and remove session
          session.serverFiber.cancel >>
            session.postResponseQueue.offer(None) >>
            session.persistentQueue.offer(None) >>
            session.requestQueue.offer(None) >>
            sessionsRef.update(_ - id)
        case None =>
          Async[F].unit
      }
    } yield ()

  def setCapabilities(id: Option[SessionId], caps: ClientCapabilities): F[Unit] =
    sessionsRef.update { sessions =>
      sessions.get(id) match {
        case Some(state) =>
          sessions + (id -> state.copy(capabilities = Some(caps)))
        case None =>
          sessions
      }
    }

  def getCapabilities(id: Option[SessionId]): F[Option[ClientCapabilities]] =
    sessionsRef.get.map(_.get(id).flatMap(_.capabilities))

  def appendEvent(id: Option[SessionId], msg: JsonRpcResponse): F[EventId] =
    sessionsRef.modify { sessions =>
      sessions.get(id) match {
        case Some(state) =>
          val eventId = state.nextEventId
          val newLog = (state.eventLog :+ (eventId -> msg)).takeRight(eventLogSize)
          val updatedState = state.copy(
            eventLog = newLog,
            nextEventId = eventId.next
          )
          (sessions + (id -> updatedState), eventId)

        case None =>
          (sessions, EventId.zero)
      }
    }

  def getEventsSince(id: Option[SessionId], eventId: EventId): F[Vector[(EventId, JsonRpcResponse)]] =
    sessionsRef.get.map { sessions =>
      sessions.get(id) match {
        case Some(state) =>
          state.eventLog.filter { case (id, _) => id.value > eventId.value }
        case None =>
          Vector.empty
      }
    }

  def enqueuePostResponse(id: Option[SessionId], msg: JsonRpcResponse): F[Unit] =
    getSession(id).flatMap {
      case Some(session) =>
        session.postResponseQueue.offer(Some(msg)).void
      case None =>
        Async[F].unit
    }

  def enqueuePersistent(id: Option[SessionId], msg: JsonRpcResponse): F[Unit] =
    getSession(id).flatMap {
      case Some(session) =>
        session.persistentQueue.offer(Some(msg)).void
      case None =>
        Async[F].unit
    }

  def enqueueRequest(id: Option[SessionId], request: JsonRpcRequest): F[Unit] =
    getSession(id).flatMap {
      case Some(session) =>
        session.requestQueue.offer(Some(request)).void
      case None =>
        Async[F].unit
    }
}
