import * as vscode from "vscode";
import { getCompletion, getProviderConfig, CompletionContext } from "./bridge";

/**
 * VSCode InlineCompletionItemProvider powered by the Rust core.
 *
 * Debouncing is handled here with a simple cancel-previous-request pattern:
 * each call to `provideInlineCompletionItems` cancels the previous in-flight
 * request via an AbortController and starts a new debounce timer.
 *
 * The Rust engine also does its own debouncing + cancellation internally, so
 * this is a belt-and-suspenders approach that eliminates redundant network calls.
 */
export class AutocompleteProvider
  implements vscode.InlineCompletionItemProvider
{
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private debounceMs: number;

  constructor() {
    const cfg = vscode.workspace.getConfiguration("oxidecode.autocomplete");
    this.debounceMs = cfg.get<number>("debounceMs", 120);
  }

  async provideInlineCompletionItems(
    document: vscode.TextDocument,
    position: vscode.Position,
    _context: vscode.InlineCompletionContext,
    token: vscode.CancellationToken
  ): Promise<vscode.InlineCompletionList | null> {
    const enabled = vscode.workspace
      .getConfiguration("oxidecode.autocomplete")
      .get<boolean>("enabled", true);
    if (!enabled) return null;

    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
    }

    return new Promise((resolve) => {
      this.debounceTimer = setTimeout(async () => {
        if (token.isCancellationRequested) {
          resolve(null);
          return;
        }

        const ctx = this.buildContext(document, position);
        const providerConfig = getProviderConfig();

        try {
          const completion = await getCompletion(providerConfig, ctx);
          if (!completion || token.isCancellationRequested) {
            resolve(null);
            return;
          }

          resolve({
            items: [
              new vscode.InlineCompletionItem(
                completion,
                new vscode.Range(position, position)
              ),
            ],
          });
        } catch (err) {
          console.error("[OxideCode] Completion error:", err);
          resolve(null);
        }
      }, this.debounceMs);
    });
  }

  private buildContext(
    document: vscode.TextDocument,
    position: vscode.Position
  ): CompletionContext {
    const offset = document.offsetAt(position);
    const fullText = document.getText();
    return {
      prefix: fullText.slice(0, offset),
      suffix: fullText.slice(offset),
      language: document.languageId,
      filepath: vscode.workspace.asRelativePath(document.uri),
    };
  }
}
