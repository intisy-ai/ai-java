# ai-java `:examples`

A runnable, never-published showcase of the entire `:jvm` consumer API. Read `Main.java` top to
bottom, then each demo class: together they show how a Java server embeds ai-java — pick a storage
backend, inject every platform SPI, drop provider jars in a directory, and get routing + account
management with all platform concerns swappable.

## Run it

```
gradlew :examples:run
```

This builds `:examples-provider`'s jar, **stages it into `examples/build/providers/`**, points the
demos at that directory via the `examples.providersDir` system property, and runs every demo in
order (exit 0 on success). The integration tests run the same way:

```
gradlew :examples:test
```

## How the provider jar gets discovered

`:examples-provider` is a thin jar containing two `Provider` implementations plus a
`META-INF/services/io.github.intisy.ai.shared.routing.Provider` registration. `:examples` does **not**
depend on it as code — a Gradle `Copy` task (`stageProviders`) copies the built jar into
`build/providers/`, and `AiJava.builder().providersDir(...)` discovers both providers there purely via
`ServiceLoader` over a dedicated `URLClassLoader`. Dropping a new provider jar into that directory
needs zero example-code changes.

- `EchoProvider` (`id = "echo"`) — healthy; returns a well-formed Anthropic-messages body echoing the
  routed model.
- `AlwaysRateLimitedProvider` (`id = "ratelimited"`) — always `429` with rate-limit headers, to drive
  the router's fallback/exhaustion paths.

## What each demo shows

| Demo | Covers |
| --- | --- |
| **StorageDemo** | `storage(...)` is required (build without it throws); the same routine runs identically against `Storage.file`, `Storage.memory`, and `Storage.jdbc` (H2). |
| **CustomSpiDemo** | Injecting `jsonCodec` / `logger` / `clock` / `random` / `notifier` / `env`; a fixed clock + seeded random make backoff deterministic, and the custom logger/notifier capture the router's fallback decision. |
| **ProviderRegistryDemo** | `providersDir(...)` → `ServiceLoader` discovery (`listProviderIds`), routing through a jar-loaded provider with no registry code, and the `close()` lifecycle (try-with-resources; a fresh instance re-discovers afterward). |
| **RoutingDemo** | A realistic multi-tier `RoutingProfile`: model rewrite, cross-provider fallback (429 → healthy), tier exhaustion → synthesized native 429, and the `/v1/models` catalog. |
| **AccountManagerDemo** | `accountManager(providerId, oauth)` against a local fake token endpoint: acquire → cooldown after `reportRateLimit` → deterministic `reportError` backoff → `reportSuccess` → a stored-refresh-token refresh round trip → a revoked (`invalid_grant`) refresh disabling the account. The injected `httpClient` performs the real refresh POSTs. |
| **NotifierDemo** | A file-backed store's default `JsonlNotifier`: a routing fallback writes a JSONL notice; the demo prints the line. |

## Layout

- `src/main/java/.../examples/` — `Main` + the six demo classes (one concern each).
- `src/main/java/.../examples/support/` — the injectable SPI implementations and shared fixtures
  (clock, random, logger, notifier, env, recording codecs/clients, the fake token server, seeds,
  profiles, providers-dir locator).
- `src/test/java/.../examples/` — JUnit 5 integration tests that assert each demonstrated behavior
  end to end against the real components (fakes only at true external edges: the token endpoint,
  clock, and random).

## Integration tests

| Test | Asserts |
| --- | --- |
| `StoreParityIntegrationTest` | the shared routine yields identical stored value + routed response on file / memory / jdbc. |
| `ProviderRegistryIntegrationTest` | the real jar's two providers are discovered, route a request, and re-discover after `close()`. |
| `RoutingIntegrationTest` | model rewrite, fallback, exhaustion (429 shape), and `/v1/models` contents. |
| `AccountManagerIntegrationTest` | cooldown blocks selection, deterministic backoff, refresh updates stored JSON, `invalid_grant` disables the account. |
| `CustomSpiIntegrationTest` | injected SPIs take effect (env value, captured logs/notices, deterministic backoff, custom codec used). |
| `NotifierIntegrationTest` | a file-backed store writes a well-shaped JSONL notification line. |
