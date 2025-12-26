package mcp.server

import cats.effect.IO
import mcp.protocol.TaskStatus
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class TaskRegistrySuite extends CatsEffectSuite {

  test("create should return task with working status") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
    } yield {
      assertEquals(task.status, TaskStatus.working)
      assert(task.taskId.nonEmpty)
      assert(task.createdAt.nonEmpty)
      assert(task.lastUpdatedAt.nonEmpty)
      assertEquals(task.ttl, Some(TaskConfig.DefaultTtl.toMillis))
      assertEquals(task.pollInterval, Some(TaskConfig.DefaultPollInterval.toMillis))
    }
  }

  test("create should use provided TTL") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(Some(1.minute))
    } yield assertEquals(task.ttl, Some(60000L))
  }

  test("get should return created task") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
      fetched <- registry.get(task.taskId)
    } yield {
      assertEquals(fetched.map(_.taskId), Some(task.taskId))
      assertEquals(fetched.map(_.status), Some(TaskStatus.working))
    }
  }

  test("get should return None for unknown task") {
    for {
      registry <- TaskRegistry[IO]
      fetched <- registry.get("unknown-task-id")
    } yield assertEquals(fetched, None)
  }

  test("list should return all created tasks") {
    for {
      registry <- TaskRegistry[IO]
      task1 <- registry.create(None)
      task2 <- registry.create(None)
      tasks <- registry.list()
    } yield {
      assertEquals(tasks.length, 2)
      assert(tasks.exists(_.taskId == task1.taskId))
      assert(tasks.exists(_.taskId == task2.taskId))
    }
  }

  test("updateStatus should change task status") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
      _ <- registry.updateStatus(task.taskId, TaskStatus.completed, Some("Done"))
      updated <- registry.get(task.taskId)
    } yield {
      assertEquals(updated.map(_.status), Some(TaskStatus.completed))
      assertEquals(updated.flatMap(_.statusMessage), Some("Done"))
    }
  }

  test("cancel should transition task to cancelled") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
      cancelled <- registry.cancel(task.taskId)
    } yield assertEquals(cancelled.map(_.status), Some(TaskStatus.cancelled))
  }

  test("cancel should return None for already terminal task") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
      _ <- registry.updateStatus(task.taskId, TaskStatus.completed)
      result <- registry.cancel(task.taskId)
    } yield assertEquals(result, None)
  }

  test("cancel should return None for unknown task") {
    for {
      registry <- TaskRegistry[IO]
      result <- registry.cancel("unknown-task-id")
    } yield assertEquals(result, None)
  }

  test("storeResult and getResult should work") {
    import io.circe.Json
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
      _ <- registry.storeResult(task.taskId, Json.obj("result" -> Json.fromString("success")))
      result <- registry.getResult(task.taskId)
    } yield {
      assert(result.isDefined)
      assertEquals(result.flatMap(_.asObject).flatMap(_("result")).flatMap(_.asString), Some("success"))
    }
  }

  test("getResult should return None for task without result") {
    for {
      registry <- TaskRegistry[IO]
      task <- registry.create(None)
      result <- registry.getResult(task.taskId)
    } yield assertEquals(result, None)
  }

  test("isTerminal should be true for completed status") {
    assertEquals(TaskStatus.completed.isTerminal, true)
  }

  test("isTerminal should be true for failed status") {
    assertEquals(TaskStatus.failed.isTerminal, true)
  }

  test("isTerminal should be true for cancelled status") {
    assertEquals(TaskStatus.cancelled.isTerminal, true)
  }

  test("isTerminal should be false for working status") {
    assertEquals(TaskStatus.working.isTerminal, false)
  }

  test("isTerminal should be false for input_required status") {
    assertEquals(TaskStatus.input_required.isTerminal, false)
  }

  test("expired task should be marked as failed") {
    val shortTtlConfig = TaskConfig(defaultTtl = 10.millis, gracePeriodMultiplier = 10)
    for {
      registry <- TaskRegistry[IO](shortTtlConfig)
      task <- registry.create(None)
      _ <- IO.sleep(50.millis)
      fetched <- registry.get(task.taskId)
    } yield {
      assertEquals(fetched.map(_.status), Some(TaskStatus.failed))
      assert(fetched.flatMap(_.statusMessage).exists(_.contains("TTL exceeded")))
    }
  }

  test("task with custom TTL should expire based on that TTL") {
    val config = TaskConfig(defaultTtl = 1.second, gracePeriodMultiplier = 10)
    for {
      registry <- TaskRegistry[IO](config)
      task <- registry.create(Some(10.millis))
      _ <- IO.sleep(50.millis)
      fetched <- registry.get(task.taskId)
    } yield assertEquals(fetched.map(_.status), Some(TaskStatus.failed))
  }

  test("task within TTL should not be expired") {
    val longTtlConfig = TaskConfig(defaultTtl = 1.minute)
    for {
      registry <- TaskRegistry[IO](longTtlConfig)
      task <- registry.create(None)
      fetched <- registry.get(task.taskId)
    } yield assertEquals(fetched.map(_.status), Some(TaskStatus.working))
  }

  test("completed task should not be marked as failed on expiry") {
    val shortTtlConfig = TaskConfig(defaultTtl = 10.millis, gracePeriodMultiplier = 10)
    for {
      registry <- TaskRegistry[IO](shortTtlConfig)
      task <- registry.create(None)
      _ <- registry.updateStatus(task.taskId, TaskStatus.completed, Some("Done"))
      _ <- IO.sleep(50.millis)
      fetched <- registry.get(task.taskId)
    } yield assertEquals(fetched.map(_.status), Some(TaskStatus.completed))
  }

  test("task past grace period should be removed entirely") {
    val shortConfig = TaskConfig(defaultTtl = 5.millis, gracePeriodMultiplier = 2)
    for {
      registry <- TaskRegistry[IO](shortConfig)
      task <- registry.create(None)
      _ <- IO.sleep(50.millis)
      fetched <- registry.get(task.taskId)
    } yield assertEquals(fetched, None)
  }

  test("result for removed task should also be cleaned up") {
    import io.circe.Json
    val shortConfig = TaskConfig(defaultTtl = 5.millis, gracePeriodMultiplier = 2)
    for {
      registry <- TaskRegistry[IO](shortConfig)
      task <- registry.create(None)
      _ <- registry.storeResult(task.taskId, Json.obj("result" -> Json.fromString("test")))
      _ <- IO.sleep(50.millis)
      result <- registry.getResult(task.taskId)
    } yield assertEquals(result, None)
  }

  test("custom config should use specified poll interval") {
    val config = TaskConfig(defaultPollInterval = 500.millis)
    for {
      registry <- TaskRegistry[IO](config)
      task <- registry.create(None)
    } yield assertEquals(task.pollInterval, Some(500L))
  }
}
