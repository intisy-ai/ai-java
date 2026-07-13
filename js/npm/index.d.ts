/**
 * TypeScript declarations for @intisy-ai/ai-core's JS-side entrypoint (index.js), which wraps
 * the TeaVM-compiled build of ai-java's shared routing engine (see js/src/main/java/
 * io/github/intisy/ai/js/AiJavaJs.java for the exported Java surface this glues to).
 */

/** A pre-seeded Store snapshot: {key: jsonString}, matching shared's `Store` SPI value shape
 *  (each value is itself an opaque JSON string, e.g. `router-test.json` -> a stringified
 *  `{modelMap: {...}}` config object). */
export type StoreSnapshot = Record<string, string>;

/** Anything that can produce a Store snapshot on demand. `InMemoryStore` implements this. */
export interface StoreLike {
  snapshot(): StoreSnapshot;
}

/** Accepted shapes for the `store` option: a live StoreLike, or a plain snapshot object. */
export type StoreInit = StoreLike | StoreSnapshot;

/**
 * Plain in-memory holder for a Store SNAPSHOT. Used only by `routeJsonSyncWith` — NOT a live,
 * round-tripping Store: that call takes a one-shot snapshot of it before routing; mutations
 * Router makes during routing are not written back (see index.js's class doc for the full
 * rationale). `routeJson` uses `LiveStore` (below) instead, which has no such limitation.
 */
export class InMemoryStore implements StoreLike {
  constructor(initial?: StoreSnapshot);
  get(key: string): string | null;
  put(key: string, value: string): void;
  delete(key: string): void;
  has(key: string): boolean;
  snapshot(): StoreSnapshot;
}

/**
 * A LIVE Store: every method is synchronous and reads/writes straight through to the backing
 * storage, matching shared's `Store` SPI shape (`get`/`put`/`exists`/`delete`/`listKeys`,
 * `update` is derived as get-then-put on the Java side — see `JsStoreBridge`). Passed to
 * `routeJson`, any mutation the routing engine makes during that call (round-robin cursor
 * advance in `Selection`, rate-limit `coolingDownUntil`/`rateLimitResetTimes` writes, etc.) is
 * visible to the NEXT `routeJson` call that reuses the same instance.
 */
export interface LiveStoreLike {
  /** Returns the stored JSON string for `key`, or `null`/`undefined` when absent. */
  get(key: string): string | null | undefined;
  put(key: string, value: string): void;
  exists(key: string): boolean;
  delete(key: string): void;
  /** Every stored key starting with `prefix`. */
  listKeys(prefix: string): string[];
}

/**
 * `Map`-backed default implementation of {@link LiveStoreLike}. This is what `routeJson` uses
 * internally (lazily, module-scoped) when no `opts.store` is supplied, and is also usable
 * directly by a caller that wants an explicit, inspectable live store shared across several
 * `routeJson` calls.
 */
export class LiveStore implements LiveStoreLike {
  constructor(initial?: StoreSnapshot);
  get(key: string): string | null;
  put(key: string, value: string): void;
  exists(key: string): boolean;
  delete(key: string): void;
  listKeys(prefix: string): string[];
}

export interface RouteJsonOptions {
  /** fetch-compatible function used as the HttpClient transport for any provider handler that
   *  forwards the request upstream. Defaults to the global `fetch`. */
  fetch?: typeof fetch;
  /** LIVE store (see `LiveStore`/`LiveStoreLike`) backing the routing engine's Store for this
   *  call, and every subsequent `routeJson` call the caller passes it to again. Defaults to a
   *  package-scoped `LiveStore` instance shared across calls that omit this option. */
  store?: LiveStoreLike;
}

/**
 * Routes `requestJson` — a JSON-encoded HttpRequest (`{method, url, headers, body}`) — through
 * shared's `Router`, asynchronously, against a LIVE Store (see `RouteJsonOptions.store`). Any
 * registered provider handler that forwards the request upstream does so via a `fetch`-backed
 * HttpClient (real `fetch` by default, or `opts.fetch`), bridged into the TeaVM-compiled routing
 * engine via the `@Async`/`AsyncCallback` mechanism proven in Phase 2 Task 5.
 *
 * Resolves to a JSON-encoded HttpResponse (`{status, headers, body}`).
 */
export function routeJson(requestJson: string, opts?: RouteJsonOptions): Promise<string>;

/**
 * Synchronous variant — no HttpClient/`fetch` involved at all. Only useful when the routing
 * profile's registered handler(s) never call out (mirrors `AiJavaJs.routeJsonSync`'s canned
 * in-Java-handler shape used for the Step-1 transpile smoke test). Takes a Store SNAPSHOT (see
 * `InMemoryStore`), not a live store — mutations made during routing are discarded.
 */
export function routeJsonSyncWith(requestJson: string, opts?: { store?: StoreInit }): string;

/**
 * Parses then re-stringifies `json` through the same `JsonCodec` (`SimpleJsonCodec`) that
 * `routeJson`/`routeJsonSyncWith` use internally. Exposed for integer-fidelity verification:
 * a whole JSON number — including one outside the 32-bit `int` range, which exercises TeaVM's
 * emulated `Long` — must reserialize without a trailing `.0`, matching the JVM `GsonJsonCodec`'s
 * `LONG_OR_DOUBLE` behavior for the same input.
 */
export function jsonRoundTrip(json: string): string;

// -- Phase 2 Task 7 (JVM<->JS parity vectors) pure-function exports ---------------------------
// String(JSON)-in/string(JSON)-out wrappers over shared's RateLimitMath/RateLimit/ModelMap,
// used to run the SAME input->expected vectors (shared/src/test/resources/parity/*.json) the
// JVM parity test (jvm/src/test/.../ParityVectorsTest.java) runs, through this actually-shipped
// package. See js/src/main/java/io/github/intisy/ai/js/AiJavaJs.java for the Java side.

/**
 * `argsJson`: `{attempt: number, baseMs: number, maxMs: number, jitter: boolean}`.
 * Returns the bare JSON number result of `RateLimitMath.calculateBackoffMs` (the deterministic
 * `jitter === false` path -- `min(maxMs, baseMs * 2^max(0, attempt))`).
 */
export function calculateBackoffMsJson(argsJson: string): string;

/**
 * `argsJson`: `{headers: Record<string, string>, now: number}`.
 * Returns the bare JSON number result of `RateLimit.rateLimitResetMs` against a synthesized
 * response carrying those headers.
 */
export function rateLimitResetMsJson(argsJson: string): string;

/**
 * `profileJson`: `{tierSourceProvider, tierOrder, tierFallback, tierRegex, envPrefix}`.
 * `storeJson`: a Store snapshot (typically just a seeded `models.json`).
 * Returns the resolved tier list (`ModelMap.resolveTiers`) as a JSON array of strings.
 */
export function resolveTiersJson(profileJson: string, storeJson: string): string;

/**
 * `profileJson`: `{configFile, tierSourceProvider, tierOrder, tierFallback, tierRegex,
 * envPrefix}`. `storeJson`: a Store snapshot (the config file's `modelMap` plus `models.json`).
 * Returns `{tier: [{provider, model, name, derived}, ...]}` (`ModelMap.resolveModelMap`) as JSON.
 */
export function resolveModelMapJson(profileJson: string, storeJson: string): string;
