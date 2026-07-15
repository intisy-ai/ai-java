# ai-java `:example-server`

A runnable, never-published HTTP server that drives ai-java's `WiredRouter` over the JDK's
built-in `com.sun.net.httpserver.HttpServer` — no framework dependency. It shows how a JVM server
embeds ai-java: choose a backend, discover provider jars, and serve the shared router over HTTP.

## Run it

```
gradlew :example-server:run
```

This stages `:examples-provider`'s jar into `build/providers/`, boots on `http://127.0.0.1:8787`,
and seeds the echo catalog so requests return a real body. Override with
`-Dexampleserver.port=...`, `-Dexampleserver.store=memory|file`, `-Dexampleserver.providersDir=...`.

## Endpoints

| Method + path | Behavior |
| --- | --- |
| `POST /v1/messages` | Anthropic-messages request → routed through the tier chain to a provider jar; returns the buffered response body (the engine is fully buffered — no SSE streaming). |
| `GET /v1/models` | The router's model catalog (handled inside `Router`). |
| `GET /healthz` | Server-level liveness (`200 ok`), answered without touching the router. |

## What it demonstrates

- **Completely customizable backend:** `ServerMain` composes a `Backend` from a single store
  choice; every other SPI defaults, and any could be overridden in the same place.
- **Provider-jar discovery:** providers are found on disk via `ServiceLoader`, never on the
  server's classpath.
- **The buffered router boundary:** the server is a pure `HttpExchange` ↔ `HttpRequest`/
  `HttpResponse` adapter; all routing decisions live in `:routing`.

## Extract-to-own-repo note

Kept self-contained (depends only on `:jvm`'s public API) so it can move to its own repo once
ai-java is published as a Maven artifact — at which point it would depend on
`io.github.intisy:ai-java` instead of the composite build.
