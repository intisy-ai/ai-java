# ai-java

Server-side AI library for Java — provider auth, account/quota management, and a
routing proxy — ported from the intisy TypeScript AI stack so a JVM server can drive
the same providers as the TypeScript plugins.

`ai-java` is the **JVM host** of a single, shared AI core written in Java. The same
Java source is compiled two ways: **javac → a JVM jar** (this repo, for servers) and
**[TeaVM](https://teavm.org/) → JavaScript** (in the provider repos, for the
TypeScript plugins). One implementation of the routing, rate-limit, selection, and
OAuth-refresh logic — no second copy to drift.

## Modules

| Module | Source | Purpose |
| --- | --- | --- |
| `:jvm` | `jvm/` | The consumer-facing assembly: [`AiJava`](jvm/src/main/java/io/github/intisy/ai/jvm/AiJava.java) builder/facade plus the JVM SPI implementations (`GsonJsonCodec`, `UrlConnectionHttpClient`, `FileStore`/`InMemoryStore`/`JdbcStore`, `SystemClock`, `SecureRandomAdapter`, `JsonlNotifier`, …) and the ServiceLoader-based `ProviderRegistry`. |
| `:routing` | `core-proxy/java/routing` (submodule) | The generic routing engine: SPI interfaces, `Router` (tier fallback, model rewrite, native-429 synthesis, `/v1/models`), `ModelMap`, `RateLimit`, the `Provider` SPI, and `HandlerResolvers`. |
| `:accounts` | `core-auth/java/accounts` (submodule) | The account engine: `AccountManager` (acquire / report / backoff / refresh), `OAuthConfig`, `AccountStore`, `Selection`, `RateLimitMath`. |
| `:examples` / `:examples-provider` | `examples/`, `examples-provider/` | A runnable tour of the whole `:jvm` API with end-to-end integration tests. See [examples/README.md](examples/README.md). |
| `:example-server` | `example-server/` | A runnable, never-published HTTP server (`com.sun.net.httpserver`) that drives `WiredRouter` over `POST /v1/messages` + `GET /v1/models` + `/healthz`. See [example-server/README.md](example-server/README.md). |

`core-proxy` and `core-auth` are vendored as git submodules and composed into one
Gradle build by the authoritative root [`settings.gradle`](settings.gradle) — the
submodules' own `settings.gradle` files are not read when folded in like this.

## Requirements

- **JDK 17+** to build (the library itself is compiled and validated against the
  **Java 8** API surface — `compileJava` runs with `--release 8`, so a Java 9+ API
  leak fails the build rather than a Java 8 JRE at runtime).
- Git (submodules are cloned recursively).

## Build

```sh
git clone --recursive <repo-url>
cd ai-java
./gradlew build                 # compile + all tests
./gradlew :jvm:shadowJar        # -> jvm/build/libs/jvm-standalone.jar (gson shaded in)
./gradlew :examples:run         # run the full API tour
```

## Quickstart

Storage is the one required choice — it is never silently defaulted to files. Every
other dependency has a JVM-sane default you can override.

```java
try (AiJava ai = AiJava.builder()
        .storage(Storage.file(Paths.get("config")))   // or Storage.memory() / Storage.jdbc(dataSource)
        .providersDir(Paths.get("providers"))          // *.jar provider plugins, discovered via ServiceLoader
        .build()) {

    // Route a request through the tier-fallback chain of the discovered providers:
    AiJava.WiredRouter router = ai.router(myRoutingProfile);
    HttpResponse response = router.route(request);

    // Manage a provider's accounts (acquire under lock, refresh outside it):
    AccountManager manager = ai.accountManager("claude-code", myOAuthConfig);
    Acquired account = manager.acquire("messages");
}
```

See [`examples/`](examples/) for worked demos of storage backends, custom SPIs,
provider-jar discovery, routing (rewrite / fallback / exhaustion / `/v1/models`),
account management (cooldown / refresh / revoke), and the notifier.

The whole platform is a swappable unit: instead of overriding SPIs one at a time, compose a
`Backend` and hand it over —

```java
Backend backend = Backend.builder()
        .store(Storage.memory())
        .logger(myLogger)          // any unset SPI falls back to its JVM default
        .build();
try (AiJava ai = AiJava.builder().backend(backend).build()) { ... }
```

`Backends.defaults(store)` gives the all-defaults bundle; per-SPI setters on `AiJava.builder()`
still override whatever the backend supplies.

## License

See the repository's license.
