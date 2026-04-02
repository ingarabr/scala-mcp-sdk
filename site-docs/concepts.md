---
sidebar_position: 3
---

# Key Concepts

A quick primer on the Scala and Cats Effect patterns used throughout this SDK. If you're already comfortable with the Typelevel ecosystem, feel free to skip ahead.

## `IO` — Describing Effects

`IO[A]` describes a computation that, when run, produces a value of type `A`. Nothing happens until the runtime executes it — similar to `async`/`await` in TypeScript or Python, but explicit.

```scala
// This doesn't print anything — it describes a computation
val hello: IO[Unit] = IO.println("Hello!")

// IO.pure wraps an already-computed value (no side effects)
val result: IO[Int] = IO.pure(42)
```

Think of `IO` as a recipe. `IO.pure(x)` is a recipe that just returns `x`. Other `IO` values describe real work (network calls, file reads, etc.).

## `Resource` — Safe Setup and Cleanup

`Resource[IO, A]` pairs acquiring something with releasing it — like try-with-resources in Java. Resources compose: if any step fails, everything acquired so far is cleaned up.

```scala
// McpServer and StdioTransport are both Resources
(for {
  server    <- McpServer[IO](...)    // acquired, will be cleaned up
  transport <- StdioTransport[IO]()  // acquired, will be cleaned up
  _         <- server.serve(transport)
} yield ()).useForever  // .useForever keeps it running until interrupted
```

## `for`/`yield` — Sequencing Steps

A `for`/`yield` block chains operations that depend on each other. Each `<-` runs the previous step and binds the result.

```scala
for {
  a <- IO.pure(1)       // a = 1
  b <- IO.pure(a + 1)   // b = 2
} yield a + b            // 3
```

This is equivalent to chained `.flatMap` calls — similar to `.then()` chains or sequential `await` calls.

## `F[_]` — Generic Effect Type

Some definitions use `F[_]` instead of `IO` directly. This means "any effect type" — it makes the code reusable across different runtimes, but `IO` is always a valid choice.

```scala
// F[_]: Async means "any effect type that supports async operations"
def myResource[F[_]: Async]: ResourceDef[F, String] = ...

// When you use it, F becomes IO
val r = myResource[IO]
```

If you're just getting started, use `IO` everywhere. You can generalize later if needed.

## `given` / `using` — Implicit Resolution

`given` defines a value that the compiler passes automatically where `using` is expected. This is how the SDK resolves input schemas without you passing them explicitly.

```scala
// Define it once with 'given'
given InputDef[MyInput] = InputDef[MyInput](...)

// ToolDef picks it up automatically via 'using'
val tool = ToolDef.unstructured[IO, MyInput](...) { ... }
// No need to pass InputDef explicitly — the compiler finds it
```

This is conceptually similar to dependency injection, but resolved at compile time.

## Named Tuples — Lightweight Input Types

Scala 3 named tuples let you define structured types without a full class:

```scala
// A named tuple — like an inline case class
type GreetInput = (name: String, excited: Option[Boolean])

// Access fields by name
val input: GreetInput = (name = "Alice", excited = Some(true))
println(input.name) // "Alice"
```

You can also use case classes if you prefer — both work with `InputDef`.

## `derives` — Automatic Typeclass Instances

Scala 3's `derives` keyword generates typeclass instances at compile time. You'll see it used with Circe for JSON serialization:

```scala
case class Config(name: String, version: String) derives Codec.AsObject
// equivalent to writing:
// given Codec.AsObject[Config] = Codec.AsObject.derived
```

Think of it like `@JsonSerializable` in Kotlin or `implements Serializable` — but resolved at compile time with no reflection.

## Async Tasks and Concurrency

Every `IO` value is a task. Tasks don't run until the Cats Effect runtime executes them. You compose tasks with `for`/`yield` (sequential) or combinators like `parTupled` (parallel):

```scala
// Sequential — one after the other
for {
  a <- fetchUser(id)
  b <- fetchOrders(a.id)
} yield (a, b)

// Parallel — both run concurrently
(fetchUser(id), fetchPrices(item)).parTupled
```

Inside a tool handler, your `IO` runs on the Cats Effect fiber runtime — lightweight threads managed by the runtime, similar to goroutines or virtual threads. You don't need to manage threads manually.

The key thing to remember: returning `IO.pure(result)` from a tool handler doesn't block anything. The runtime handles scheduling, and the MCP server can serve other requests concurrently.
