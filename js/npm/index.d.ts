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
 * Plain in-memory holder for a Store snapshot. NOT a live, round-tripping Store — routeJson
 * takes a one-shot snapshot of it before each call; mutations Router makes during routing are
 * not written back (see index.js's class doc for the full rationale).
 */
export class InMemoryStore implements StoreLike {
  constructor(initial?: StoreSnapshot);
  get(key: string): string | null;
  put(key: string, value: string): void;
  delete(key: string): void;
  has(key: string): boolean;
  snapshot(): StoreSnapshot;
}

export interface RouteJsonOptions {
  /** fetch-compatible function used as the HttpClient transport for any provider handler that
   *  forwards the request upstream. Defaults to the global `fetch`. */
  fetch?: typeof fetch;
  /** Store snapshot (or a StoreLike) to seed the routing engine's Store with for this call.
   *  Defaults to an empty in-memory store. */
  store?: StoreInit;
}

/**
 * Routes `requestJson` — a JSON-encoded HttpRequest (`{method, url, headers, body}`) — through
 * shared's `Router`, asynchronously. Any registered provider handler that forwards the request
 * upstream does so via a `fetch`-backed HttpClient (real `fetch` by default, or `opts.fetch`),
 * bridged into the TeaVM-compiled routing engine via the `@Async`/`AsyncCallback` mechanism
 * proven in Phase 2 Task 5.
 *
 * Resolves to a JSON-encoded HttpResponse (`{status, headers, body}`).
 */
export function routeJson(requestJson: string, opts?: RouteJsonOptions): Promise<string>;

/**
 * Synchronous variant — no HttpClient/`fetch` involved at all. Only useful when the routing
 * profile's registered handler(s) never call out (mirrors `AiJavaJs.routeJsonSync`'s canned
 * in-Java-handler shape used for the Step-1 transpile smoke test).
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
