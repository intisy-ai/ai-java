// TS consumer test for the @intisy-ai/ai-core package (Phase 2 Task 6). Imports the built
// package exactly as an external consumer would, provides a MOCK async `fetch` (a genuinely
// delayed Promise, not a same-tick resolve) plus an in-memory Store seeded with a modelMap
// config, calls routeJson(...), and asserts the routed JSON. Bundled with esbuild and run under
// node (mirrors the Phase 2 Task 5 spike's app.ts -> out.mjs flow) — see scripts/run-consumer-test.mjs.
import { routeJson, InMemoryStore, jsonRoundTrip } from "../index.js";

let failed = false;
function check(condition: boolean, message: string): void {
  if (!condition) {
    failed = true;
    console.error(`FAIL: ${message}`);
  } else {
    console.log(`ok: ${message}`);
  }
}

// -- 1. routeJson through a mock fetch + seeded in-memory Store -------------------------------

const store = new InMemoryStore();
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

await runAsyncCheck();
runIntegerFidelityCheck();

if (failed) {
  console.error("CONSUMER TEST FAILED");
  process.exit(1);
}
console.log(
  "CONSUMER TEST OK — @intisy-ai/ai-core's routeJson() round-tripped a mocked fetch-backed HttpClient " +
    "through the TeaVM-compiled Router, and jsonRoundTrip() preserved integer fidelity (incl. Long range)."
);
