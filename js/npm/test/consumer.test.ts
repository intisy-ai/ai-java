// TS consumer test for the @intisy-ai/ai-core package (Phase 2 Task 6; Phase 3 Task 1 adds the
// LIVE-store writeback proof in section 4 below). Imports the built package exactly as an
// external consumer would, provides a MOCK async `fetch` (a genuinely delayed Promise, not a
// same-tick resolve) plus a live Store seeded with a modelMap config, calls routeJson(...), and
// asserts the routed JSON. Bundled with esbuild and run under node (mirrors the Phase 2 Task 5
// spike's app.ts -> out.mjs flow) — see scripts/run-consumer-test.mjs.
import { routeJson, LiveStore, jsonRoundTrip } from "../index.js";

let failed = false;
function check(condition: boolean, message: string): void {
  if (!condition) {
    failed = true;
    console.error(`FAIL: ${message}`);
  } else {
    console.log(`ok: ${message}`);
  }
}

// -- 1. routeJson through a mock fetch + seeded LIVE Store -------------------------------------

const store = new LiveStore();
store.put(
  "router-test.json",
  JSON.stringify({ modelMap: { default: [{ provider: "test", model: "m-test" }] } })
);

const requestJson = JSON.stringify({
  method: "POST",
  url: "/v1/messages",
  headers: { "x-in": "1" },
  body: JSON.stringify({ hello: "world" }),
});

let fetchCallCount = 0;

// A Response-shaped mock: .status, .headers.forEach, .text() — the exact surface index.js's
// makeHttpSend() consumes. Resolves after a REAL setTimeout (50ms), not Promise.resolve on the
// same tick, so a broken/no-op async bridge could not fake a pass (same discipline as Task 5).
const mockFetch: typeof fetch = ((_url: string, _init?: RequestInit) => {
  fetchCallCount++;
  return new Promise((resolve) => {
    setTimeout(() => {
      const bodyText = JSON.stringify({
        echoedMethod: "POST",
        upstream: "canned-upstream-response",
      });
      resolve({
        status: 200,
        headers: { forEach: (cb: (v: string, k: string) => void) => cb("application/json", "content-type") },
        text: async () => bodyText,
      } as unknown as Response);
    }, 50);
  });
}) as typeof fetch;

async function runAsyncCheck(): Promise<void> {
  const before = Date.now();
  const result = await routeJson(requestJson, { fetch: mockFetch, store });
  const elapsed = Date.now() - before;

  console.log("elapsedMs:", elapsed, "fetchCallCount:", fetchCallCount);
  console.log("result:", result);

  const parsed = JSON.parse(result) as { status: number; body: string };
  check(parsed.status === 200, `status is 200 (got ${parsed.status})`);

  const upstream = JSON.parse(parsed.body) as { upstream: string; echoedMethod: string };
  check(upstream.upstream === "canned-upstream-response", "upstream marker present in routed body");
  check(upstream.echoedMethod === "POST", "request method forwarded to the mocked fetch");
  check(fetchCallCount === 1, `exactly one fetch call (got ${fetchCallCount})`);
  check(elapsed >= 45, `resolved after a genuine event-loop delay (elapsed=${elapsed}ms, expected >= 45ms)`);
}

// -- 2. integer fidelity: a whole number (incl. one outside 32-bit int range) round-trips ----
// without a trailing ".0" — matching the JVM GsonJsonCodec's LONG_OR_DOUBLE behavior for the
// same input (see jvm/src/test/.../GsonJsonCodecTest.wholeNumberRoundTripsWithoutTrailingZero
// and jvm/src/main/.../GsonJsonCodec's javadoc).

function runIntegerFidelityCheck(): void {
  // 1752345678901 is an epoch-ms timestamp: > Integer.MAX_VALUE (2147483647), so this can only
  // parse correctly as a Java Long — exercising TeaVM's emulated 64-bit Long end-to-end through
  // SimpleJsonCodec, the exact codec routeJson/routeJsonSyncWith use internally.
  const input = JSON.stringify({ expires: 1752345678901, count: 5, fraction: 0.5 });
  const output = jsonRoundTrip(input);
  console.log("jsonRoundTrip:", output);

  check(output.includes('"expires":1752345678901'), "large Long-range integer round-trips without .0");
  check(!output.includes("1752345678901.0"), "no spurious .0 suffix on the Long-range integer");
  check(output.includes('"count":5'), "small whole number round-trips as a bare integer");
  check(!output.includes('"count":5.0'), "no spurious .0 suffix on the small integer");
  check(output.includes('"fraction":0.5'), "fractional number keeps its decimal point");

  const parsedBack = JSON.parse(output) as { expires: number };
  check(parsedBack.expires === 1752345678901, "round-tripped value is numerically exact in JS too");
}

// -- 3. reject path: a fetch that REJECTS must surface as a 502 JSON error, not a hang or an ----
// unhandled rejection. Router.route() catches the HttpClient exception thrown when
// JsHttpClientBridge's @Async bridge resumes via AsyncCallback.error(...) and turns it into
// errorResponse(502, ...) -- see shared's Router.java catch around handler.handle(...).

const mockFetchReject: typeof fetch = ((_url: string, _init?: RequestInit) => {
  return new Promise((_resolve, reject) => {
    setTimeout(() => reject(new Error("simulated network failure")), 30);
  });
}) as typeof fetch;

async function runRejectPathCheck(): Promise<void> {
  let threw = false;
  let result = "";
  try {
    result = await routeJson(requestJson, { fetch: mockFetchReject, store });
  } catch (e) {
    threw = true;
    console.error("routeJson threw instead of resolving on a rejecting fetch:", e);
  }
  check(!threw, "routeJson resolves (does not throw/hang) when the supplied fetch rejects");
  if (threw) return;

  const parsed = JSON.parse(result) as { status: number; body: string };
  check(parsed.status === 502, `rejecting fetch surfaces as a 502 (got ${parsed.status})`);

  const body = JSON.parse(parsed.body) as { type?: string; error?: { type?: string; message?: string } };
  check(body.type === "error", `502 body has type "error" (got ${JSON.stringify(body)})`);
  check(
    !!body.error && typeof body.error.message === "string" && body.error.message.length > 0,
    "502 body carries a non-empty error message"
  );
}

// -- 4. LIVE store writeback: a mutation from call 1 must persist and be observed by call 2 ----
// (Phase 3 Task 1 — THE key test). AiJavaJs's "test" provider handler claims an account via
// AccountStore/Selection (ROUND_ROBIN) before forwarding, purely so this test has something
// real to observe: seed 2 accounts under the "test" provider in a fresh LiveStore, then call
// routeJson() TWICE against the SAME store instance. If the Store were still a one-shot
// snapshot (the pre-Phase-3 behavior), Selection would see activeIndex reset to 0 on every call
// and BOTH calls would round-robin-advance to the SAME account (index 1). Seeing the cursor
// actually alternate across the two calls proves the mutation from call 1 was written back to
// the JS store, not discarded.

const persistFetch: typeof fetch = (() => {
  return Promise.resolve({
    status: 200,
    headers: { forEach: (_cb: (v: string, k: string) => void) => {} },
    text: async () => JSON.stringify({ ok: true }),
  } as unknown as Response);
}) as typeof fetch;

async function runStoreWritebackPersistenceCheck(): Promise<void> {
  const liveStore = new LiveStore();
  // Same modelMap seed as section 1 -- Router needs a resolved {provider,model} chain for the
  // "test" tier before it will even invoke the "test" provider handler that does the account
  // selection this check is actually about.
  liveStore.put(
    "router-test.json",
    JSON.stringify({ modelMap: { default: [{ provider: "test", model: "m-test" }] } })
  );
  liveStore.put(
    "accounts.json",
    JSON.stringify({
      version: 1,
      providers: {
        test: {
          accounts: [
            { id: "acc-a", enabled: true },
            { id: "acc-b", enabled: true },
          ],
          activeIndex: 0,
          activeIndexByLane: {},
        },
      },
    })
  );

  const call1 = JSON.parse(
    await routeJson(requestJson, { fetch: persistFetch, store: liveStore })
  ) as { status: number; headers: Record<string, string> };
  const call2 = JSON.parse(
    await routeJson(requestJson, { fetch: persistFetch, store: liveStore })
  ) as { status: number; headers: Record<string, string> };

  console.log("writeback call1 x-account-id:", call1.headers["x-account-id"]);
  console.log("writeback call2 x-account-id:", call2.headers["x-account-id"]);

  check(call1.status === 200, `writeback call1 status is 200 (got ${call1.status})`);
  check(call2.status === 200, `writeback call2 status is 200 (got ${call2.status})`);
  check(
    call1.headers["x-account-id"] === "acc-b",
    `writeback call1 round-robins from activeIndex 0 to account "acc-b" (got ${call1.headers["x-account-id"]})`
  );
  check(
    call2.headers["x-account-id"] === "acc-a",
    `writeback call2 picks the NEXT account "acc-a", proving call1's cursor advance was ` +
      `persisted rather than discarded (got ${call2.headers["x-account-id"]})`
  );
  check(
    call1.headers["x-account-id"] !== call2.headers["x-account-id"],
    "the two calls selected DIFFERENT accounts -- Selection's round-robin cursor advance from " +
      "call 1 was written back to the live store and observed by call 2 (a snapshot Store " +
      "would show the SAME account picked both times)"
  );

  // Independent corroboration straight from the persisted JSON (not just the response header):
  // two round-robin picks over a 2-account pool starting at activeIndex 0 cycle 0 -> 1 -> 0.
  const accountsDoc = JSON.parse(liveStore.get("accounts.json") as string) as {
    providers: { test: { activeIndex: number } };
  };
  check(
    accountsDoc.providers.test.activeIndex === 0,
    `persisted activeIndex cycled 0->1->0 across the two calls (got ${accountsDoc.providers.test.activeIndex})`
  );
}

await runAsyncCheck();
runIntegerFidelityCheck();
await runRejectPathCheck();
await runStoreWritebackPersistenceCheck();

if (failed) {
  console.error("CONSUMER TEST FAILED");
  process.exit(1);
}
console.log(
  "CONSUMER TEST OK — @intisy-ai/ai-core's routeJson() round-tripped a mocked fetch-backed HttpClient " +
    "through the TeaVM-compiled Router, jsonRoundTrip() preserved integer fidelity (incl. Long range), " +
    "a rejecting fetch resolved to a native 502 with no hang/unhandled rejection, and a mutation " +
    "made during one routeJson() call (Selection's round-robin cursor advance) was written back to " +
    "the live JS store and observed by a second routeJson() call reusing it."
);
