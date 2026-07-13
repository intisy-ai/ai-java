// Phase 2 Task 7: type-checks test/parity.test.ts against index.d.ts (tsc --noEmit), then
// bundles it with esbuild and runs it under node -- same shape as run-consumer-test.mjs, kept
// as a separate script (and a separate "test:parity" npm script) so the two consumer tests
// produce independently readable pass/fail output.
import { spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const npmDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const isWin = process.platform === "win32";

function run(label, cmd, args) {
  console.log(`[ai-core parity] ${label}: ${cmd} ${args.join(" ")}`);
  const result = spawnSync(cmd, args, { cwd: npmDir, stdio: "inherit", shell: isWin });
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

run("typecheck", "npx", ["--yes", "tsc", "--noEmit", "-p", "tsconfig.json"]);

const outDir = path.join(npmDir, ".test-out");
mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, "parity.test.mjs");

run("bundle", "npx", [
  "--yes",
  "esbuild",
  "test/parity.test.ts",
  "--bundle",
  "--platform=node",
  "--format=esm",
  `--outfile=${outFile}`,
]);

console.log("[ai-core parity] running under node ...");
const runResult = spawnSync(process.execPath, [outFile], { cwd: npmDir, stdio: "inherit" });
process.exit(runResult.status ?? 1);
