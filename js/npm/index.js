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
  resolveTiers as resolveTiersRaw,
  resolveModelMap as resolveModelMapRaw,
  acquireAccount as acquireAccountRaw,
  reportRateLimit as reportRateLimitRaw,
  reportError as reportErrorRaw,
  reportSuccess as reportSuccessRaw,
  nextAvailableAt as nextAvailableAtRaw,
  accessTokenExpired as accessTokenExpiredRaw,
  refreshToken as refreshTokenRaw,
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

// -- Phase 3 Task 1: production fine-grained core API (over the LIVE store) ------------------
// Clean, ergonomic (object-in/object-out, not raw JSON strings) wrappers over the SAME AiJavaJs
// exports the parity vectors above use internally, at the granularity a TS provider
// driver/loader actually calls them at: resolve the model map/tiers, select+claim an account,
// report an outcome, ask when the pool next frees up, and (kept separate, so a driver can
// interleave it with its own proxy-aware fetch) refresh an OAuth access token. Every one of
// these — except `refreshToken`, the only one that talks to the network — takes the same `opts`
// shape as `routeJson`: `{store?: LiveStoreLike}`, resolved via `toLiveStore` so state persists
// across calls exactly like `routeJson`'s.

/**
 * `ModelMap.resolveTiers` over the LIVE store's `models.json` (the tier-source provider's
 * catalog). Returns the resolved tier list as a plain string array.
 *
 * @param {object} profile {tierSourceProvider, tierOrder, tierFallback, tierRegex, envPrefix}
 * @param {{store?: object}} [opts]
 * @returns {string[]}
 */
export function resolveTiers(profile, opts = {}) {
  const store = toLiveStore(opts.store);
  return JSON.parse(String(resolveTiersRaw(JSON.stringify(profile), store)));
}

/**
 * `ModelMap.resolveModelMap` (heal/derive) over the LIVE store: reads `profile.configFile`'s
 * `modelMap` plus `models.json`'s live catalog. Returns `{tier: [{provider,model,name,derived},
 * ...]}`.
 *
 * @param {object} profile {configFile, tierSourceProvider, tierOrder, tierFallback, tierRegex, envPrefix}
 * @param {{store?: object}} [opts]
 * @returns {Record<string, Array<{provider: string, model: string, name: string, derived: boolean}>>}
 */
export function resolveModelMap(profile, opts = {}) {
  const store = toLiveStore(opts.store);
  return JSON.parse(String(resolveModelMapRaw(JSON.stringify(profile), store)));
}

/**
 * `AccountManager.selectAndClaim` — selection + the `lastUsed` claim ONLY (the store write
 * persists via the live store); NO network refresh (see `refreshToken` below). Returns
 * `{accountId, access?}` (the claimed account's CURRENT stored access token, possibly
 * stale/expired — check via `accessTokenExpired`), or `{none: true}` when nobody in the pool is
 * available.
 *
 * @param {string} providerId
 * @param {string | null} [lane]
 * @param {{store?: object}} [opts]
 * @returns {{accountId: string, access?: string} | {none: true}}
 */
export function acquireAccount(providerId, lane = null, opts = {}) {
  const store = toLiveStore(opts.store);
  return JSON.parse(String(acquireAccountRaw(providerId, lane, store)));
}

/**
 * `AccountManager.reportRateLimit` — persists `account.rateLimitResetTimes[lane] = resetMs`.
 *
 * @param {string} providerId
 * @param {string} id
 * @param {string | null} lane
 * @param {number} resetMs
 * @param {{store?: object}} [opts]
 */
export function reportRateLimit(providerId, id, lane, resetMs, opts = {}) {
  const store = toLiveStore(opts.store);
  reportRateLimitRaw(providerId, id, lane, resetMs, store);
}

/**
 * `AccountManager.reportError` — persists a deterministic-backoff `coolingDownUntil`/
 * `cooldownReason`.
 *
 * @param {string} providerId
 * @param {string} id
 * @param {number} attempt
 * @param {string | null} [reason]
 * @param {{store?: object}} [opts]
 */
export function reportError(providerId, id, attempt, reason = null, opts = {}) {
  const store = toLiveStore(opts.store);
  reportErrorRaw(providerId, id, attempt, reason, store);
}

/**
 * `AccountManager.reportSuccess` — clears cooldown, bumps `lastUsed`.
 *
 * @param {string} providerId
 * @param {string} id
 * @param {{store?: object}} [opts]
 */
export function reportSuccess(providerId, id, opts = {}) {
  const store = toLiveStore(opts.store);
  reportSuccessRaw(providerId, id, store);
}

/**
 * `AccountManager.nextAvailableAt` — the soonest epoch-ms any account in the pool becomes
 * available for `lane`, or `null` if none ever will.
 *
 * @param {string} providerId
 * @param {string | null} [lane]
 * @param {{store?: object}} [opts]
 * @returns {number | null}
 */
export function nextAvailableAt(providerId, lane = null, opts = {}) {
  const store = toLiveStore(opts.store);
  return JSON.parse(String(nextAvailableAtRaw(providerId, lane, store)));
}

/**
 * `TokenRefresh.accessTokenExpired` — pure predicate, no store/network involved.
 *
 * @param {{access?: string | null, expires?: number | null}} account
 * @param {number} now epoch ms
 * @returns {boolean}
 */
export function accessTokenExpired(account, now) {
  return accessTokenExpiredRaw(JSON.stringify(account ?? {}), now);
}

/**
 * `TokenRefresh.refresh` — the network OAuth refresh call, via a `fetch`-backed transport (same
 * bridge `routeJson` uses). Deliberately does NOT persist the result to any store — the caller
 * decides when/whether to write it back. Resolves to `{access, expires, refresh}` on success, or
 * `{revoked: true}` when the token endpoint reported `error=invalid_grant`. Any OTHER failure
 * (network error, non-2xx/non-invalid_grant, unparseable body) rejects.
 *
 * @param {string} refreshTokenValue
 * @param {{tokenUrl: string, clientId: string, clientSecret?: string, extraParams?: Record<string,string>}} oauthConfig
 * @param {{fetch?: typeof fetch}} [opts]
 * @returns {Promise<{access: string, expires: number, refresh: string} | {revoked: true}>}
 */
export async function refreshToken(refreshTokenValue, oauthConfig, opts = {}) {
  const fetchImpl = opts.fetch || defaultFetch();
  const httpSend = makeHttpSend(fetchImpl);
  const result = await refreshTokenRaw(refreshTokenValue, JSON.stringify(oauthConfig), httpSend);
  return JSON.parse(String(result));
}
