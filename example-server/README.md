# ai-java `:example-server`

A runnable, never-published HTTP server that drives ai-java's `WiredRouter` over the JDK's
built-in `com.sun.net.httpserver.HttpServer` — no framework dependency. It shows how a JVM server
embeds ai-java: choose a backend, discover provider jars, and serve the shared router over HTTP.

## Run it

```
gradlew :example-server:run
```

This stages `:examples-provider`'s jar into `build/providers/`, boots on `http://127.0.0.1:8787`,
and seeds the echo catalog plus two demo accounts (`demo-1@example`, `demo-2@example`) so requests
and the dashboard both return real content out of the box.

## Endpoints

| Method + path | Behavior |
| --- | --- |
| `GET /` | The dashboard: an HTML page listing discovered providers and their accounts (see below). |
| `GET /api/providers` | Management API: `[{"id":..,"accounts":<int>}]` for every discovered (installed + loaded) provider. |
| `GET /api/providers/available` | Management API: `[{"name":..,"assetName":..,"installed":bool}]` for every provider the configured `ProviderSource` can install. |
| `POST /api/providers/install` | Management API: body `{"name":"<entry name>"}` — downloads that provider's jar and refreshes the live registry so it's immediately usable; `404` on an unknown name, `502` if the download fails. |
| `GET /api/providers/{id}/accounts` | Management API: UI-safe account views (`id`, `email`, `enabled`, computed `status`) for one provider. |
| `POST /api/providers/{id}/accounts/{accId}/enable` | Management API: enable an account (`204`). |
| `POST /api/providers/{id}/accounts/{accId}/disable` | Management API: disable an account (`204`). |
| `DELETE /api/providers/{id}/accounts/{accId}` | Management API: remove an account (`204`). |
| `POST /v1/messages` | Anthropic-messages request → routed through the tier chain to a provider jar; returns the buffered response body (the engine is fully buffered — no SSE streaming). |
| `GET /v1/models` | The router's model catalog (handled inside `Router`). |
| `GET /healthz` | Server-level liveness (`200 ok`), answered without touching the router. |

## Dashboard

`GET /` serves a self-contained HTML page (no JS framework, no external assets) that lists every
provider the registry discovered and, for each, its accounts with their computed status (`ready`,
`cooling`, `rate-limited`, `disabled`). It reads through the same `AccountAdmin` the `/api/*`
management endpoints use, so the two surfaces always agree. It never shows secrets (refresh/access
tokens) or transient reasons (cooldown/disabled reason text) — only the UI-safe view.

## Discovery

At startup, provider jars are resolved by `ProviderDiscovery.resolve(providersDir)`, which only
ever scans disk — it never touches the network:

- `-Dexampleserver.providersDir=...` — directory scanned for provider `*.jar`s (default `providers`,
  overridden to `build/providers` by the Gradle `run`/`test` tasks after staging `:examples-provider`).

## On-demand install

New providers don't require a restart. `GithubOrgProviderSource` (wired in `ServerMain`) lists
provider-jar release assets published across the `intisy-ai` GitHub org; the install API downloads
one on request and hot-swaps it into the live registry via `ProviderRegistryHolder.refresh`:

- `GET /api/providers/available` — what's installable right now, each entry flagged `installed`
  if its jar already exists in `providersDir`.
- `POST /api/providers/install` with `{"name":"<entry name>"}` — downloads the matching entry's
  jar into `providersDir`, then refreshes the registry in place. The response's `providers` array
  reflects the registry immediately after the refresh, so the newly installed provider is usable
  by the very next `/v1/messages` request — no restart needed. A later task adds a dashboard UI
  over this same API.

Other flags:

- `-Dexampleserver.store=memory|file` — the backing `Store` (default `memory`); `file` persists
  under `-Dexampleserver.configDir=...` (default `config`).
- `-Dexampleserver.port=...` — the HTTP port (default `8787`; `0` picks an ephemeral port).

## What it demonstrates

- **Completely customizable backend:** `ServerMain` composes a `Backend` from a single store
  choice; every other SPI defaults, and any could be overridden in the same place.
- **Provider-jar discovery:** providers already on disk are found via `ServiceLoader`, never on
  the server's classpath.
- **On-demand install with live refresh:** a provider can be downloaded and made routable while
  the server keeps running, via a swappable `ProviderRegistryHolder`.
- **The buffered router boundary:** the server is a pure `HttpExchange` ↔ `HttpRequest`/
  `HttpResponse` adapter; all routing decisions live in `:routing`.
- **Account administration:** `AccountAdmin` + `ManagementApi` expose enable/disable/remove over
  HTTP, and the dashboard renders the same data for humans.

## Extract-to-own-repo note

Kept self-contained (depends only on `:jvm`'s public API) so it can move to its own repo once
ai-java is published as a Maven artifact — at which point it would depend on
`io.github.intisy:ai-java` instead of the composite build.
