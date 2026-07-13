// Type-checks test/consumer.test.ts against index.d.ts (tsc --noEmit — esbuild alone would
// silently strip type errors), then bundles it with esbuild (mirrors the Phase 2 Task 5 spike's
// app.ts -> out.mjs flow) and runs the bundle under node.
import { spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const npmDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const isWin = process.platform === "win32";

function run(label, cmd, args) {
  console.log(`[ai-core test] ${label}: ${cmd} ${args.join(" ")}`);
  const result = spawnSync(cmd, args, { cwd: npmDir, stdio: "inherit", shell: isWin });
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

run("typecheck", "npx", ["--yes", "tsc", "--noEmit", "-p", "tsconfig.json"]);

const outDir = path.join(npmDir, ".test-out");
mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, "consumer.test.mjs");

run("bundle", "npx", [
  "--yes",
  "esbuild",
  "test/consumer.test.ts",
  "--bundle",
  "--platform=node",
  "--format=esm",
  `--outfile=${outFile}`,
]);

console.log("[ai-core test] running under node ...");
const runResult = spawnSync(process.execPath, [outFile], { cwd: npmDir, stdio: "inherit" });
process.exit(runResult.status ?? 1);
