/**
 * Thin wrapper around the napi-rs compiled native addon.
 *
 * The .node file is placed next to the extension's dist/ folder during build.
 * We load it lazily so the extension can activate even if the native binary
 * hasn't been built yet (graceful degradation).
 */

import * as path from "path";
import * as vscode from "vscode";

export interface ProviderConfig {
  baseUrl: string;
  apiKey: string | null;
  model: string;
  completionModel: string | null;
}

export interface CompletionContext {
  prefix: string;
  suffix: string;
  language: string;
  filepath: string;
}

export interface EditDelta {
  filepath: string;
  startLine: number;
  startCol: number;
  removed: string;
  inserted: string;
  fileContent: string;
  timestampMs: number;
}

export interface NesHint {
  filepath: string;
  line: number;
  col: number;
  replacement: string;
  removeStartLine?: number;
  removeStartCol?: number;
  removeEndLine?: number;
  removeEndCol?: number;
  confidence?: number;
}

let native: typeof import("../oxidecode.node") | null = null;

function getNative(): typeof import("../oxidecode.node") {
  if (!native) {
    const addonPath = path.join(__dirname, "..", "oxidecode.node");
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    native = require(addonPath);
  }
  return native!;
}

export async function getCompletion(
  providerConfig: ProviderConfig,
  ctx: CompletionContext
): Promise<string | null> {
  return getNative().getCompletion(providerConfig, ctx);
}

export async function predictNextEdit(
  providerConfig: ProviderConfig,
  deltas: EditDelta[],
  cursorFilepath: string,
  cursorLine: number,
  cursorCol: number,
  fileContent: string,
  language: string
): Promise<NesHint | null> {
  return getNative().predictNextEdit(
    providerConfig,
    deltas,
    cursorFilepath,
    cursorLine,
    cursorCol,
    fileContent,
    language
  );
}

export function initLogging(): void {
  try {
    getNative().initLogging();
  } catch {
    // Silently ignore — logging is non-critical
  }
}

export function getProviderConfig(): ProviderConfig {
  const cfg = vscode.workspace.getConfiguration("oxidecode.provider");
  return {
    baseUrl: cfg.get<string>("baseUrl", "http://localhost:11434"),
    apiKey: cfg.get<string>("apiKey", "") || null,
    model: cfg.get<string>("model", "qwen2.5-coder:7b"),
    completionModel: cfg.get<string>("completionModel", "") || null,
  };
}
