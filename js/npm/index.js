// JS entrypoint: wraps the TeaVM-generated ESM (dist/aijava.js, built from :js's
// AiJavaJs/JsHttpClientBridge/SimpleJsonCodec — see js/src/main/java/io/github/intisy/ai/js) with
// the JS-side SPI implementations shared's blocking-shaped HttpClient/Store need: a real
// `fetch`-based transport and a plain in-memory Store. The String/JSON boundary proven in
// Phase 2 Task 5 is preserved end-to-end: every function below is string-in/string-out (or a
// Promise of one) at the point it crosses into the TeaVM module.
import { routeJsonAsync, routeJsonSync, jsonRoundTrip } from "./dist/aijava.js";

/**
 * Plain in-memory Store snapshot holder. Values are opaque JSON strings, matching shared's
 * `Store` SPI contract (e.g. `store.put("router-test.json", JSON.stringify({modelMap: {...}}))`).
 * This is NOT a live JSO-bridged Store — routeJson takes a one-shot snapshot of it before each
 * call (mirrors AiJavaJs.seedStore, which seeds a fresh Java-side InMemoryStore per call); any
 * mutation Router makes during routing (selection cursors, rate-limit backoff, etc.) is scoped
 * to that single call and is not written back. A live, round-tripping JS Store is a separate,
 * larger follow-up (see Phase 2 Task 5 report, "Store is Java-side in-memory, not JSO-bridged").
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
 * via a fetch-backed HttpClient. Resolves to the JSON-encoded HttpResponse.
 *
 * @param {string} requestJson
 * @param {{fetch?: typeof fetch, store?: object}} [opts]
 * @returns {Promise<string>}
 */
export async function routeJson(requestJson, opts = {}) {
  const fetchImpl = opts.fetch || defaultFetch();
  const store = toStore(opts.store);
  const storeJson = JSON.stringify(store.snapshot());
  const httpSend = makeHttpSend(fetchImpl);
  const result = await routeJsonAsync(httpSend, storeJson, requestJson);
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
