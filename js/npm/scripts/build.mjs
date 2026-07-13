// Regenerates the TeaVM ESM (via the root Gradle build's :js:generateJavaScript task) and
// copies it into this package's dist/ dir, alongside the hand-written glue (index.js/.d.ts,
// which live at the package root and are committed — only dist/ is generated + gitignored).
import { spawnSync } from "node:child_process";
import { mkdirSync, copyFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const npmDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const jsDir = path.dirname(npmDir); // ai-java/js
const repoRoot = path.dirname(jsDir); // ai-java/

const gradlew = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const gradlewPath = path.join(repoRoot, gradlew);
if (!existsSync(gradlewPath)) {
  throw new Error(`gradlew not found at ${gradlewPath} — is this checked out inside the ai-java repo?`);
}

console.log("[ai-core build] running :js:generateJavaScript ...");
const result = spawnSync(gradlewPath, [":js:generateJavaScript", "--console=plain"], {
  cwd: repoRoot,
  stdio: "inherit",
  shell: process.platform === "win32",
});
if (result.status !== 0) {
  throw new Error(`gradlew :js:generateJavaScript failed with exit code ${result.status}`);
}

const generated = path.join(jsDir, "build", "generated", "teavm", "js", "aijava.js");
if (!existsSync(generated)) {
  throw new Error(`expected TeaVM output at ${generated} but it does not exist`);
}

const distDir = path.join(npmDir, "dist");
mkdirSync(distDir, { recursive: true });
const dest = path.join(distDir, "aijava.js");
copyFileSync(generated, dest);
console.log(`[ai-core build] copied ${generated} -> ${dest}`);
