// TS consumer test for @intisy-ai/ai-core's Phase 3 Task 1 production fine-grained core API:
// resolveModelMap/resolveTiers (over a LIVE store), acquireAccount (select+claim, no refresh),
// reportRateLimit/reportError/reportSuccess/nextAvailableAt (AccountManager reporters), and
// refreshToken (the async OAuth network call, kept separate from acquireAccount per the task's
// "TS interleaves proxy/fetch" design). Imports the built package exactly as an external
// consumer would (mirrors test/consumer.test.ts's shape) — see scripts/run-fine-grained-test.mjs.
import {
  LiveStore,
  resolveModelMap,
  resolveTiers,
  acquireAccount,
  reportRateLimit,
  reportError,
  reportSuccess,
  nextAvailableAt,
  accessTokenExpired,
  refreshToken,
} from "../index.js";

let failed = false;
function check(condition: boolean, message: string): void {
  if (!condition) {
    failed = true;
    console.error(`FAIL: ${message}`);
  } else {
    console.log(`ok: ${message}`);
  }
}

// -- 1. resolveModelMap / resolveTiers over a seeded LIVE store --------------------------------
// Seed models.json with a live catalog + router-test.json with a stale modelMap entry so
// resolveModelMap has to HEAL it within the chosen provider (proves it reads the LIVE store, not
// a hardcoded snapshot).

function runResolveModelMapCheck(): void {
  const store = new LiveStore();
  store.put(
    "models.json",
    JSON.stringify({
      test: {
        models: { "model-fable-1": {}, "model-opus-9": {} },
        ranking: ["model-fable-1", "model-opus-9"],
      },
    })
  );
  // Stored mapping points at a model NOT in the catalog above -- resolveModelMap must heal it
  // within the "test" provider (never cross to a different provider).
  store.put(
    "router-test.json",
    JSON.stringify({ modelMap: { fable: [{ provider: "test", model: "model-fable-stale" }] } })
  );

  const profile = {
    configFile: "router-test.json",
    tierSourceProvider: "test",
    tierOrder: ["fable", "opus"],
    tierFallback: ["fable"],
    tierRegex: "^model-([a-z]+)-\\d",
    envPrefix: "TEST",
  };

  const tiers = resolveTiers(profile, { store });
  console.log("resolveTiers:", tiers);
  check(JSON.stringify(tiers) === JSON.stringify(["fable", "opus"]), `resolveTiers returns detected tiers in order (got ${JSON.stringify(tiers)})`);

  const modelMap = resolveModelMap(profile, { store });
  console.log("resolveModelMap:", JSON.stringify(modelMap));
  check(Array.isArray(modelMap.fable), "resolveModelMap has a 'fable' tier");
  check(
    modelMap.fable.length === 1 && modelMap.fable[0].model === "model-fable-1" && modelMap.fable[0].derived === true,
    `stale 'fable' mapping healed within provider "test" to model-fable-1 (got ${JSON.stringify(modelMap.fable)})`
  );
}

// -- 2. acquireAccount selects+persists; a second call rotates via the live store --------------

function runAcquireAccountCheck(): void {
  const store = new LiveStore();
  store.put(
    "accounts.json",
    JSON.stringify({
      version: 1,
      providers: {
        acme: {
          accounts: [
            { id: "acc-a", enabled: true, access: "access-a" },
            { id: "acc-b", enabled: true, access: "access-b" },
          ],
          activeIndex: 0,
          activeIndexByLane: {},
        },
      },
    })
  );

  const first = acquireAccount("acme", null, { store });
  console.log("acquireAccount call1:", first);
  check("accountId" in first, `call1 claimed an account (got ${JSON.stringify(first)})`);
  const firstId = (first as { accountId: string }).accountId;
  check(firstId === "acc-b", `call1 round-robins from activeIndex 0 to "acc-b" (got ${firstId})`);
  check((first as { access?: string }).access === "access-b", "call1's access is the account's raw stored token (no refresh)");

  const second = acquireAccount("acme", null, { store });
  console.log("acquireAccount call2:", second);
  const secondId = (second as { accountId: string }).accountId;
  check(secondId === "acc-a", `call2 picks the NEXT account "acc-a", proving call1's claim persisted (got ${secondId})`);
  check(firstId !== secondId, "the two acquireAccount calls selected DIFFERENT accounts");

  const emptyStore = new LiveStore();
  const none = acquireAccount("nobody-provider", null, { store: emptyStore });
  console.log("acquireAccount emptyPool:", none);
  check((none as { none?: true }).none === true, `acquireAccount on an empty pool returns {none:true} (got ${JSON.stringify(none)})`);
}

// -- 3. reportRateLimit -> nextAvailableAt / next acquireAccount skips the rate-limited account -

function runReportRateLimitCheck(): void {
  const store = new LiveStore();
  store.put(
    "accounts.json",
    JSON.stringify({
      version: 1,
      providers: {
        acme2: {
          accounts: [
            { id: "acc-x", enabled: true },
            { id: "acc-y", enabled: true },
          ],
          activeIndex: 0,
          activeIndexByLane: {},
        },
      },
    })
  );

  const now = Date.now();
  const claimed1 = acquireAccount("acme2", "messages", { store }) as { accountId: string };
  reportRateLimit("acme2", claimed1.accountId, "messages", now + 60_000, { store });

  const next = nextAvailableAt("acme2", "messages", { store });
  console.log("nextAvailableAt after reportRateLimit:", next);
  check(typeof next === "number" && next! > now, `nextAvailableAt reflects the rate-limit cooldown (got ${next})`);

  const claimed2 = acquireAccount("acme2", "messages", { store }) as { accountId: string };
  console.log("acquireAccount after reportRateLimit:", claimed2);
  check(
    claimed2.accountId !== claimed1.accountId,
    `next acquireAccount skips the rate-limited account "${claimed1.accountId}" (got ${claimed2.accountId})`
  );

  reportSuccess("acme2", claimed2.accountId, { store });
  reportError("acme2", claimed2.accountId, 0, "boom", { store });
  const afterError = nextAvailableAt("acme2", "messages", { store });
  console.log("nextAvailableAt after reportError:", afterError);
  check(typeof afterError === "number", `reportError sets a cooldown observable via nextAvailableAt (got ${afterError})`);

  const disabledStore = new LiveStore();
  disabledStore.put(
    "accounts.json",
    JSON.stringify({ version: 1, providers: { none: { accounts: [{ id: "d", enabled: false }], activeIndex: 0, activeIndexByLane: {} } } })
  );
  const neverAvailable = nextAvailableAt("none", null, { store: disabledStore });
  check(neverAvailable === null, `nextAvailableAt is null when no account will ever become available (got ${neverAvailable})`);
}

// -- 4. accessTokenExpired skew ------------------------------------------------------------------

function runAccessTokenExpiredCheck(): void {
  const now = 1_000_000;
  check(accessTokenExpired({ access: "tok", expires: now + 60_000 }, now) === true, "expires exactly at the 60s skew buffer counts as expired");
  check(accessTokenExpired({ access: "tok", expires: now + 60_001 }, now) === false, "expires just beyond the buffer is NOT expired");
  check(accessTokenExpired({ expires: now + 999_999 }, now) === true, "missing access token counts as expired");
  check(accessTokenExpired({ access: "tok" }, now) === true, "missing expires counts as expired");
}

// -- 5. refreshToken via a mock fetch: success returns a new access token ----------------------

async function runRefreshTokenSuccessCheck(): Promise<void> {
  const mockFetch: typeof fetch = ((_url: string, init?: RequestInit) => {
    const body = String(init?.body ?? "");
    check(body.includes("grant_type=refresh_token"), "refreshToken POSTs grant_type=refresh_token");
    check(body.includes("refresh_token=old-refresh"), "refreshToken forwards the supplied refresh token");
    return Promise.resolve({
      status: 200,
      headers: { forEach: (_cb: (v: string, k: string) => void) => {} },
      text: async () => JSON.stringify({ access_token: "new-access", expires_in: 3600, refresh_token: "new-refresh" }),
    } as unknown as Response);
  }) as typeof fetch;

  const result = await refreshToken(
    "old-refresh",
    { tokenUrl: "https://example.com/token", clientId: "client-123" },
    { fetch: mockFetch }
  );
  console.log("refreshToken success:", result);
  check("access" in result && (result as { access: string }).access === "new-access", `refreshToken resolves with the new access token (got ${JSON.stringify(result)})`);
  check((result as { refresh: string }).refresh === "new-refresh", "refreshToken resolves with the rotated refresh token");
  check(typeof (result as { expires: number }).expires === "number", "refreshToken resolves with a numeric expires");
}

// -- 6. refreshToken via a mock fetch: a 400 invalid_grant resolves {revoked:true} -------------

async function runRefreshTokenRevokedCheck(): Promise<void> {
  const mockFetchRevoked: typeof fetch = ((_url: string, _init?: RequestInit) => {
    return Promise.resolve({
      status: 400,
      headers: { forEach: (_cb: (v: string, k: string) => void) => {} },
      text: async () => JSON.stringify({ error: "invalid_grant" }),
    } as unknown as Response);
  }) as typeof fetch;

  const result = await refreshToken(
    "revoked-refresh",
    { tokenUrl: "https://example.com/token", clientId: "client-123" },
    { fetch: mockFetchRevoked }
  );
  console.log("refreshToken revoked:", result);
  check((result as { revoked?: true }).revoked === true, `refreshToken resolves {revoked:true} on invalid_grant (got ${JSON.stringify(result)})`);
}

// -- 7. refreshToken via a mock fetch: a non-revocation failure REJECTS the promise ------------

async function runRefreshTokenOtherErrorRejectsCheck(): Promise<void> {
  const mockFetchServerError: typeof fetch = ((_url: string, _init?: RequestInit) => {
    return Promise.resolve({
      status: 500,
      headers: { forEach: (_cb: (v: string, k: string) => void) => {} },
      text: async () => JSON.stringify({ error: "server_error" }),
    } as unknown as Response);
  }) as typeof fetch;

  let threw = false;
  try {
    await refreshToken(
      "some-refresh",
      { tokenUrl: "https://example.com/token", clientId: "client-123" },
      { fetch: mockFetchServerError }
    );
  } catch (e) {
    threw = true;
    console.log("refreshToken 500 rejection (expected):", e);
  }
  check(threw, "refreshToken REJECTS on a non-revocation failure (500 server_error), rather than resolving");
}

runResolveModelMapCheck();
runAcquireAccountCheck();
runReportRateLimitCheck();
runAccessTokenExpiredCheck();
await runRefreshTokenSuccessCheck();
await runRefreshTokenRevokedCheck();
await runRefreshTokenOtherErrorRejectsCheck();

if (failed) {
  console.error("FINE-GRAINED API TEST FAILED");
  process.exit(1);
}
console.log(
  "FINE-GRAINED API TEST OK — resolveModelMap/resolveTiers healed a stale mapping over a LIVE " +
    "store, acquireAccount selected+persisted (rotating on a second call), reportRateLimit made " +
    "nextAvailableAt/the next acquireAccount skip the cooled-down account, accessTokenExpired's " +
    "60s skew buffer held, and refreshToken round-tripped a mocked OAuth endpoint (success, " +
    "invalid_grant -> {revoked:true}, and a genuine failure -> promise rejection)."
);
