/**
 * Builds the native Rust addon (oxidecode.node) and copies it next to
 * the extension's dist/ directory so the bundle can require() it at runtime.
 * Supports both Node.js and Bun runtimes.
 */
const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const isBun = typeof Bun !== "undefined";
const runtime = isBun ? "Bun" : "Node.js";

const root = path.join(__dirname, "..", "..");
const bindingDir = path.join(root, "bindings", "node");
const outDir = path.join(__dirname, "..");

console.log(`[OxideCode] Building native addon (runtime: ${runtime})...`);

// Use bun's shell if available, otherwise fall back to execSync
if (isBun) {
  const proc = Bun.spawnSync(
      ["cargo", "build", "--release", "-p", "oxidecode-node"],
      { cwd: root, stdout: "inherit", stderr: "inherit" }
  );
  if (proc.exitCode !== 0) {
    throw new Error(`cargo build failed with exit code ${proc.exitCode}`);
  }
} else {
  execSync("cargo build --release -p oxidecode-node", {
    cwd: root,
    stdio: "inherit",
  });
}

const platform = isBun ? process.platform : process.platform; // same API, kept explicit
const ext = platform === "win32" ? "dll" : platform === "darwin" ? "dylib" : "so";
const prefix = platform === "win32" ? "" : "lib";
const src = path.join(root, "target", "release", `${prefix}oxidecode_node.${ext}`);
const dst = path.join(outDir, "oxidecode.node");

// Use Bun.file + Bun.write for potentially faster I/O, fall back to fs
if (isBun) {
  const data = fs.readFileSync(src); // Bun supports fs too; no async needed here
  fs.writeFileSync(dst, data);
} else {
  fs.copyFileSync(src, dst);
}

console.log(`[OxideCode] Native addon copied to ${dst} via ${runtime}`);