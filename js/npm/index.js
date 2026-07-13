// JS entrypoint: wraps the TeaVM-generated ESM (dist/aijava.js, built from :js's
// AiJavaJs/JsHttpClientBridge/SimpleJsonCodec — see js/src/main/java/io/github/intisy/ai/js) with
// the JS-side SPI implementations shared's blocking-shaped HttpClient/Store need: a real
// `fetch`-based transport and a plain in-memory Store. The String/JSON boundary proven in
// Phase 2 Task 5 is preserved end-to-end: every function below is string-in/string-out (or a
// Promise of one) at the point it crosses into the TeaVM module.
import {
  routeJsonAsync,
  routeJsonSync,
  jsonRoundTrip,
  calculateBackoffMsJson,
  rateLimitResetMsJson,
  resolveTiersJson,
  resolveModelMapJson,
} from "./dist/aijava.js";

/**
 * Plain in-memory Store SNAPSHOT holder. Values are opaque JSON strings, matching shared's
 * `Store` SPI contract (e.g. `store.put("router-test.json", JSON.stringify({modelMap: {...}}))`).
 * Used ONLY by `routeJsonSyncWith` (mirrors `AiJavaJs.seedStore`, which seeds a fresh Java-side
 * `InMemoryStore` per call): any mutation Router makes during that call (selection cursors,
 * rate-limit backoff, etc.) is scoped to that single call and is not written back. `routeJson`
 * (the async/production entrypoint) uses `LiveStore` below instead — a real, round-tripping
 * Store — so THAT path does not have this limitation.
 */
export class InMemoryStore {
  constructor(initial = {}) {
    this._data = new Map(Object.entries(initial));
  }

  get(key) {
    return this._data.has(key) ? this._data.get(key) : null;
  }

  put(key, value) {
    this._data.set(key, value);
  }

  delete(key) {
    this._data.delete(key);
  }

  has(key) {
    return this._data.has(key);
  }

  snapshot() {
    return Object.fromEntries(this._data);
  }
}

function toStore(store) {
  if (store == null) return new InMemoryStore();
  if (typeof store.snapshot === "function") return store;
  // Plain {key: jsonString} snapshot object, passed straight through.
  return new InMemoryStore(store);
}

/**
 * LIVE Store: `get`/`put`/`exists`/`delete`/`listKeys` are all synchronous and operate directly
 * on the backing `Map` — no snapshot/restore step. This is the shape `JsStoreBridge` (the
 * TeaVM/Java side — see js/src/main/java/io/github/intisy/ai/js/JsStoreBridge.java) expects:
 * every mutation the routing engine makes during a `routeJson()` call (round-robin cursor
 * advance in `Selection`, rate-limit `coolingDownUntil`/`rateLimitResetTimes` writes, etc.)
 * lands directly on THIS Map, so it is visible to the next `routeJson()` call that reuses the
 * same `LiveStore` instance — the actual point of a "live" (as opposed to snapshot) Store.
 *
 * A caller may pass any duck-typed object exposing these five synchronous methods instead of
 * this class (e.g. one backed by a file or a database) — `routeJson` just forwards whatever it
 * receives straight to the compiled Java bridge.
 */
export class LiveStore {
  constructor(initial = {}) {
    this._data = new Map(Object.entries(initial));
  }

  get(key) {
    return this._data.has(key) ? this._data.get(key) : null;
  }

  put(key, value) {
    this._data.set(key, value);
  }

  exists(key) {
    return this._data.has(key);
  }

  delete(key) {
    this._data.delete(key);
  }

  listKeys(prefix) {
    const out = [];
    for (const key of this._data.keys()) {
      if (key.startsWith(prefix)) out.push(key);
    }
    return out;
  }
}

// Module-scoped default LIVE store, lazily created: if a caller never passes opts.store to
// routeJson(), successive calls on THIS package instance still share state (rather than each
// silently getting a fresh, empty store) — matching what "live" is supposed to mean by default.
let defaultLiveStore = null;

function toLiveStore(store) {
  if (store != null) return store; // duck-typed: assumed to implement get/put/exists/delete/listKeys
  if (defaultLiveStore == null) defaultLiveStore = new LiveStore();
  return defaultLiveStore;
}

function defaultFetch() {
  if (typeof fetch === "function") return fetch;
  throw new Error(
    "ai-core: no global fetch available on this runtime; pass opts.fetch explicitly."
  );
}

// Builds the JS-side async HttpClient the TeaVM @Async bridge (JsHttpClientBridge) expects:
// (requestJson: string) => Promise<string>. Request/response are plain JSON objects shaped
// exactly like shared's spi.http.HttpRequest/HttpResponse ({method,url,headers,body} /
// {status,headers,body}) — see JsHttpClientBridge.send/parseResponse.
function makeHttpSend(fetchImpl) {
  return async function httpSend(requestJsonText) {
    const req = JSON.parse(requestJsonText);
    const method = req.method || "GET";
    const hasBody = req.body != null && req.body !== "" && method !== "GET" && method !== "HEAD";

    let response;
    try {
      response = await fetchImpl(req.url, {
        method,
        headers: req.headers || {},
        body: hasBody ? req.body : undefined,
      });
    } catch (err) {
      // Rejecting here propagates through JsHttpClientBridge's AsyncCallback.error -> Router's
      // existing catch around handler.handle(...) -> a native 502 (proven in Task 5's reject-path
      // check) — no hang, no unhandled rejection.
      throw new Error(`fetch failed for ${method} ${req.url}: ${err && err.message ? err.message : err}`);
    }

    const bodyText = await response.text();
    const headers = {};
    response.headers.forEach((value, key) => {
      headers[key] = value;
    });
    return JSON.stringify({ status: response.status, headers, body: bodyText });
  };
}

/**
 * Routes requestJson (the JSON-encoded HttpRequest) through shared's Router asynchronously,
 * via a fetch-backed HttpClient and a LIVE Store (see `LiveStore`/`toLiveStore` above): any
 * mutation shared's routing logic makes during this call (round-robin cursor advance, rate-limit
 * writes, ...) persists on `opts.store` (or the package-scoped default when omitted), so it is
 * visible to the next `routeJson()` call — unlike `routeJsonSyncWith`, which only ever sees a
 * one-shot snapshot. Resolves to the JSON-encoded HttpResponse.
 *
 * @param {string} requestJson
 * @param {{fetch?: typeof fetch, store?: object}} [opts]
 * @returns {Promise<string>}
 */
export async function routeJson(requestJson, opts = {}) {
  const fetchImpl = opts.fetch || defaultFetch();
  const store = toLiveStore(opts.store);
  const httpSend = makeHttpSend(fetchImpl);
  const result = await routeJsonAsync(httpSend, store, requestJson);
  return String(result);
}

/**
 * Synchronous variant — no HttpClient/fetch involved at all (mirrors AiJavaJs.routeJsonSync's
 * canned-in-Java-handler shape; only useful when no registered provider handler calls out).
 *
 * @param {string} requestJson
 * @param {{store?: object}} [opts]
 * @returns {string}
 */
export function routeJsonSyncWith(requestJson, opts = {}) {
  const store = toStore(opts.store);
  const storeJson = JSON.stringify(store.snapshot());
  return String(routeJsonSync(storeJson, requestJson));
}

export { jsonRoundTrip };

// -- Phase 2 Task 7 (JVM<->JS parity vectors) pure-function exports ---------------------------
// Thin passthroughs to AiJavaJs's exported wrappers (see js/src/main/java/io/github/intisy/ai/js/
// AiJavaJs.java) -- string-in/string-out (JSON), no Store/HttpClient plumbing needed. Used by
// js/npm/test/parity.test.ts to run the SAME input->expected vectors
// (shared/src/test/resources/parity/*.json) the JVM parity test
// (jvm/src/test/java/io/github/intisy/ai/jvm/ParityVectorsTest.java) runs, through the actually-
// shipped npm package rather than a reimplementation.
export { calculateBackoffMsJson, rateLimitResetMsJson, resolveTiersJson, resolveModelMapJson };
