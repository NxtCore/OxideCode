import * as vscode from "vscode";
import { AutocompleteProvider } from "./autocomplete";
import { NesProvider } from "./nes";
import { initLogging } from "./bridge";

export function activate(context: vscode.ExtensionContext): void {
  initLogging();

  // ── Autocomplete ─────────────────────────────────────────────────────────
  const autocompleteProvider = new AutocompleteProvider();
  context.subscriptions.push(
    vscode.languages.registerInlineCompletionItemProvider(
      { pattern: "**" },
      autocompleteProvider
    )
  );

  // ── NES ───────────────────────────────────────────────────────────────────
  const nesProvider = new NesProvider(context);

  context.subscriptions.push(
    vscode.workspace.onDidChangeTextDocument((event) => {
      nesProvider.onDocumentChange(event);
    }),

    vscode.commands.registerCommand("oxidecode.acceptNes", () => {
      nesProvider.acceptHint();
    }),

    vscode.commands.registerCommand("oxidecode.dismissNes", () => {
      nesProvider.dismissHint();
    }),

    vscode.commands.registerCommand("oxidecode.clearNesHistory", () => {
      nesProvider.clearHistory();
      vscode.window.showInformationMessage("OxideCode: NES edit history cleared.");
    })
  );
}

export function deactivate(): void {}
