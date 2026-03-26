/**
 * Builds the native Rust addon (oxidecode.node) and copies it next to
 * the extension's dist/ directory so the bundle can require() it at runtime.
 */
const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..", "..", "..");
const bindingDir = path.join(root, "bindings", "node");
const outDir = path.join(__dirname, "..");

console.log("[OxideCode] Building native addon...");
execSync("cargo build --release -p oxidecode-node", {
  cwd: root,
  stdio: "inherit",
});

const ext = process.platform === "win32" ? "dll" : process.platform === "darwin" ? "dylib" : "so";
const prefix = process.platform === "win32" ? "" : "lib";
const src = path.join(root, "target", "release", `${prefix}oxidecode_node.${ext}`);
const dst = path.join(outDir, "oxidecode.node");

fs.copyFileSync(src, dst);
console.log(`[OxideCode] Native addon copied to ${dst}`);
