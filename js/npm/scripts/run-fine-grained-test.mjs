// Phase 3 Task 1: type-checks test/fine-grained.test.ts against index.d.ts (tsc --noEmit), then
// bundles it with esbuild and runs it under node -- same shape as run-consumer-test.mjs/
// run-parity-test.mjs, kept as its own script (and its own "test:fine-grained" npm script) so
// this suite's pass/fail output is independently readable.
import { spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const npmDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const isWin = process.platform === "win32";

function run(label, cmd, args) {
  console.log(`[ai-core fine-grained] ${label}: ${cmd} ${args.join(" ")}`);
  const result = spawnSync(cmd, args, { cwd: npmDir, stdio: "inherit", shell: isWin });
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

run("typecheck", "npx", ["--yes", "tsc", "--noEmit", "-p", "tsconfig.json"]);

const outDir = path.join(npmDir, ".test-out");
mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, "fine-grained.test.mjs");

run("bundle", "npx", [
  "--yes",
  "esbuild",
  "test/fine-grained.test.ts",
  "--bundle",
  "--platform=node",
  "--format=esm",
  `--outfile=${outFile}`,
]);

console.log("[ai-core fine-grained] running under node ...");
const runResult = spawnSync(process.execPath, [outFile], { cwd: npmDir, stdio: "inherit" });
process.exit(runResult.status ?? 1);
