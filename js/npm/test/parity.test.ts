// JS parity harness (Phase 2 Task 7). Loads the SAME vector files the JVM parity test
// (jvm/src/test/java/io/github/intisy/ai/jvm/ParityVectorsTest.java) reads from
// shared/src/test/resources/parity/, and runs each vector through the ACTUALLY-SHIPPED npm
// package's exported functions (not a reimplementation) -- proving the TeaVM-compiled JS build
// of shared's routing engine produces IDENTICAL output to the JVM for rate-limit backoff math,
// regex tier extraction, heal/derive model-map resolution, and JSON integer fidelity. See
// scripts/run-parity-test.mjs for how this file is typechecked, bundled, and run.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import {
  calculateBackoffMsJson,
  rateLimitResetMsJson,
  resolveTiersJson,
  resolveModelMapJson,
  jsonRoundTrip,
} from "../index.js";

const npmDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const jsDir = path.dirname(npmDir); // ai-java/js
const repoRoot = path.dirname(jsDir); // ai-java/
const parityDir = path.join(repoRoot, "shared", "src", "test", "resources", "parity");

function loadVectors(name: string): any[] {
  return JSON.parse(readFileSync(path.join(parityDir, name), "utf8"));
}

let failed = false;
function check(condition: boolean, message: string): void {
  if (!condition) {
    failed = true;
    console.error(`FAIL: ${message}`);
  } else {
    console.log(`ok: ${message}`);
  }
}

// -- 1. calculateBackoffMs ---------------------------------------------------------------

for (const v of loadVectors("backoff.json")) {
  const result = JSON.parse(
    calculateBackoffMsJson(
      JSON.stringify({ attempt: v.attempt, baseMs: v.baseMs, maxMs: v.maxMs, jitter: v.jitter })
    )
  );
  check(
    result === v.expectedMs,
    `backoff id=${v.id}: got ${result}, expected ${v.expectedMs}`
  );
}

// -- 2. rateLimitResetMs ---------------------------------------------------------------

for (const v of loadVectors("rate-limit-reset.json")) {
  const result = JSON.parse(rateLimitResetMsJson(JSON.stringify({ headers: v.headers, now: v.now })));
  check(
    result === v.expectedMs,
    `rateLimitResetMs id=${v.id}: got ${result}, expected ${v.expectedMs}`
  );
}

// -- 3. resolveTiers (regex tier extraction) -- the highest JVM<->JS regex-divergence risk ----

for (const v of loadVectors("tier-extraction.json")) {
  const models: Record<string, object> = {};
  for (const id of v.modelIds as string[]) models[id] = {};
  const storeJson = JSON.stringify({
    "models.json": JSON.stringify({ [v.tierSourceProvider]: { models, ranking: v.modelIds } }),
  });
  const profileJson = JSON.stringify({
    tierSourceProvider: v.tierSourceProvider,
    tierOrder: v.tierOrder,
    tierFallback: v.tierFallback,
    tierRegex: v.tierRegex,
    envPrefix: "TEST",
  });
  const result = JSON.parse(resolveTiersJson(profileJson, storeJson));
  check(
    JSON.stringify(result) === JSON.stringify(v.expectedTiers),
    `tier-extraction id=${v.id}: got ${JSON.stringify(result)}, expected ${JSON.stringify(v.expectedTiers)}`
  );
}

// -- 4. resolveModelMap (heal/derive) ---------------------------------------------------

for (const v of loadVectors("heal-derive.json")) {
  const profileJson = JSON.stringify(v.profile);
  const storeJson = JSON.stringify(v.store);
  const result = JSON.parse(resolveModelMapJson(profileJson, storeJson));

  const expectedKeys = Object.keys(v.expected).sort();
  const actualKeys = Object.keys(result).sort();
  check(
    JSON.stringify(actualKeys) === JSON.stringify(expectedKeys),
    `heal-derive id=${v.id}: tier keys ${JSON.stringify(actualKeys)}, expected ${JSON.stringify(expectedKeys)}`
  );
  for (const tier of expectedKeys) {
    check(
      JSON.stringify(result[tier]) === JSON.stringify(v.expected[tier]),
      `heal-derive id=${v.id} tier=${tier}: got ${JSON.stringify(result[tier])}, expected ${JSON.stringify(v.expected[tier])}`
    );
  }
}

// -- 5. JSON integer fidelity (whole numbers round-trip without a spurious .0) ----------------

for (const v of loadVectors("json-integer-fidelity.json")) {
  const output = jsonRoundTrip(v.input);
  for (const substr of (v.expectContains ?? []) as string[]) {
    check(output.includes(substr), `json-integer-fidelity id=${v.id}: expected output to contain "${substr}" (got ${output})`);
  }
  for (const substr of (v.expectNotContains ?? []) as string[]) {
    check(!output.includes(substr), `json-integer-fidelity id=${v.id}: expected output NOT to contain "${substr}" (got ${output})`);
  }
}

if (failed) {
  console.error("PARITY TEST FAILED");
  process.exit(1);
}
console.log(
  "PARITY TEST OK — JS outputs matched the JVM parity vectors' expected values for backoff math, " +
    "rate-limit reset, regex tier extraction, heal/derive model-map resolution, and JSON integer fidelity."
);
