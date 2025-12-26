package mcp.server

import cats.effect.{Async, Clock, Fiber, Ref}
import cats.syntax.all.*
import io.circe.Json
import mcp.protocol.{Task, TaskStatus}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Registry for managing async tasks and their results. */
trait TaskRegistry[F[_]] {

  /** Create a new task with working status. */
  def create(ttl: Option[FiniteDuration]): F[Task]

  /** Get a task by ID. */
  def get(taskId: String): F[Option[Task]]

  /** List all active tasks. */
  def list(): F[List[Task]]

  /** Cancel a task. Returns None if task not found or already terminal. */
  def cancel(taskId: String): F[Option[Task]]

  /** Update task status. */
  def updateStatus(taskId: String, status: TaskStatus, message: Option[String] = None): F[Unit]

  /** Store the result of a completed task. */
  def storeResult(taskId: String, result: Json): F[Unit]

  /** Get the stored result for a task. */
  def getResult(taskId: String): F[Option[Json]]

  /** Register a fiber for a task (for cancellation support). */
  def registerFiber(taskId: String, fiber: Fiber[F, Throwable, Unit]): F[Unit]
}

/** Configuration for task TTL and polling behavior. */
case class TaskConfig(
    /** Default TTL for tasks when not specified by client. */
    defaultTtl: FiniteDuration = TaskConfig.DefaultTtl,
    /** Default poll interval. */
    defaultPollInterval: FiniteDuration = TaskConfig.DefaultPollInterval,
    /** Grace period multiplier - tasks kept for ttl * gracePeriodMultiplier after expiration. */
    gracePeriodMultiplier: Int = TaskConfig.DefaultGracePeriodMultiplier
)

object TaskConfig {

  /** Default TTL: 1 hour */
  val DefaultTtl: FiniteDuration = 1.hour

  /** Default poll interval: 1 second */
  val DefaultPollInterval: FiniteDuration = 1.second

  /** Default grace period multiplier: 10x TTL */
  val DefaultGracePeriodMultiplier: Int = 10
}

object TaskRegistry {

  /** Create a new TaskRegistry instance with default configuration. */
  def apply[F[_]: Async]: F[TaskRegistry[F]] =
    apply(TaskConfig())

  /** Create a new TaskRegistry instance with custom configuration. */
  def apply[F[_]: Async](config: TaskConfig): F[TaskRegistry[F]] =
    for {
      tasks <- Ref.of[F, Map[String, TaskEntry[F]]](Map.empty)
      results <- Ref.of[F, Map[String, Json]](Map.empty)
    } yield new TaskRegistryImpl[F](tasks, results, config)
}

/** Internal entry tracking a task with its execution fiber. */
private[server] case class TaskEntry[F[_]](
    task: Task,
    fiber: Option[Fiber[F, Throwable, Unit]] = None
)

private class TaskRegistryImpl[F[_]](
    tasks: Ref[F, Map[String, TaskEntry[F]]],
    results: Ref[F, Map[String, Json]],
    config: TaskConfig
)(using F: Async[F])
    extends TaskRegistry[F] {

  private def currentTimeIso: F[String] =
    Clock[F].realTimeInstant.map(_.toString)

  private def currentTimeMillis: F[Long] =
    Clock[F].realTime.map(_.toMillis)

  def create(ttl: Option[FiniteDuration]): F[Task] =
    for {
      taskId <- F.delay(UUID.randomUUID().toString)
      now <- currentTimeIso
      task = Task(
        taskId = taskId,
        status = TaskStatus.working,
        createdAt = now,
        lastUpdatedAt = now,
        ttl = Some(ttl.getOrElse(config.defaultTtl).toMillis),
        pollInterval = Some(config.defaultPollInterval.toMillis)
      )
      _ <- tasks.update(_ + (taskId -> TaskEntry(task)))
    } yield task

  def get(taskId: String): F[Option[Task]] =
    for {
      _ <- expireAndCleanup()
      task <- tasks.get.map(_.get(taskId).map(_.task))
    } yield task

  def list(): F[List[Task]] =
    for {
      _ <- expireAndCleanup()
      tasks <- tasks.get.map(_.values.map(_.task).toList)
    } yield tasks

  def cancel(taskId: String): F[Option[Task]] =
    for {
      _ <- expireAndCleanup()
      now <- currentTimeIso
      result <- tasks.modify { taskMap =>
        taskMap.get(taskId) match {
          case Some(entry) if entry.task.status.isTerminal =>
            (taskMap, None)
          case Some(entry) =>
            val updatedTask = entry.task.copy(
              status = TaskStatus.cancelled,
              lastUpdatedAt = now
            )
            val updatedMap = taskMap.updated(taskId, entry.copy(task = updatedTask))
            (updatedMap, Some((entry.fiber, updatedTask)))
          case None =>
            (taskMap, None)
        }
      }
      _ <- result.traverse { case (fiberOpt, _) =>
        fiberOpt.traverse_(_.cancel)
      }
    } yield result.map(_._2)

  def updateStatus(taskId: String, status: TaskStatus, message: Option[String] = None): F[Unit] =
    for {
      now <- currentTimeIso
      _ <- tasks.update { taskMap =>
        taskMap.get(taskId) match {
          case Some(entry) =>
            val updatedTask = entry.task.copy(
              status = status,
              statusMessage = message,
              lastUpdatedAt = now
            )
            taskMap.updated(taskId, entry.copy(task = updatedTask))
          case None => taskMap
        }
      }
    } yield ()

  def storeResult(taskId: String, result: Json): F[Unit] =
    results.update(_ + (taskId -> result))

  def getResult(taskId: String): F[Option[Json]] =
    for {
      _ <- expireAndCleanup()
      result <- results.get.map(_.get(taskId))
    } yield result

  def registerFiber(taskId: String, fiber: Fiber[F, Throwable, Unit]): F[Unit] =
    tasks.update { taskMap =>
      taskMap.get(taskId) match {
        case Some(entry) => taskMap.updated(taskId, entry.copy(fiber = Some(fiber)))
        case None        => taskMap
      }
    }

  /** Lazy cleanup: expire non-terminal tasks past TTL, remove tasks past grace period.
    *
    * This is called on every read operation (get, list, getResult, cancel) to ensure TTL enforcement without requiring a background
    * process.
    */
  private def expireAndCleanup(): F[Unit] =
    for {
      nowMillis <- currentTimeMillis
      nowIso <- currentTimeIso
      fibersToCancel <- tasks.modify { taskMap =>
        val defaultTtlMillis = config.defaultTtl.toMillis
        val initial: (Map[String, TaskEntry[F]], List[Fiber[F, Throwable, Unit]]) =
          (Map.empty, List.empty)
        val (updatedMap, fibersToCancel) = taskMap.foldLeft(initial) { case ((acc, fibers), (taskId, entry)) =>
          val task = entry.task
          val ttlMillis = task.ttl.getOrElse(defaultTtlMillis)
          val createdAtMillis = parseIsoToMillis(task.createdAt)
          val expiresAt = createdAtMillis + ttlMillis
          val graceEndsAt = createdAtMillis + ttlMillis * config.gracePeriodMultiplier

          if nowMillis > graceEndsAt then {
            // Past grace period - remove task entirely
            (acc, fibers)
          } else if nowMillis > expiresAt && !task.status.isTerminal then {
            // Expired but within grace - mark as failed, cancel fiber
            val updatedTask = task.copy(
              status = TaskStatus.failed,
              statusMessage = Some("Task expired (TTL exceeded)"),
              lastUpdatedAt = nowIso
            )
            val updatedEntry: TaskEntry[F] = TaskEntry(updatedTask, None)
            val newFibers = entry.fiber.toList ++ fibers
            (acc + (taskId -> updatedEntry), newFibers)
          } else {
            // Still valid - keep as is
            (acc + (taskId -> entry), fibers)
          }
        }
        (updatedMap, fibersToCancel)
      }
      // Cancel fibers for expired tasks
      _ <- fibersToCancel.traverse_(_.cancel)
      // Cleanup results for removed tasks
      _ <- cleanupOrphanedResults()
    } yield ()

  /** Remove results for tasks that no longer exist. */
  private def cleanupOrphanedResults(): F[Unit] =
    for {
      taskIds <- tasks.get.map(_.keySet)
      resultIds <- results.get.map(_.keySet)
      orphaned = resultIds -- taskIds
      _ <- if orphaned.nonEmpty then results.update(_ -- orphaned) else F.unit
    } yield ()

  /** Parse ISO 8601 timestamp to milliseconds. */
  private def parseIsoToMillis(iso: String): Long =
    try Instant.parse(iso).toEpochMilli
    catch case _: Exception => 0L
}

extension (status: TaskStatus) {

  /** Check if the status is terminal (completed, failed, or cancelled). */
  def isTerminal: Boolean = status match {
    case TaskStatus.completed | TaskStatus.failed | TaskStatus.cancelled => true
    case _                                                               => false
  }
}
