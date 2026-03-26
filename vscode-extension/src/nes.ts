import * as vscode from "vscode";
import { predictNextEdit, getProviderConfig, EditDelta, NesHint } from "./bridge";

/**
 * NES (Next Edit Suggestion) provider.
 *
 * Tracks edit history per-document and, after a debounce delay, asks the
 * Rust core to predict where the next edit should be and what it should say.
 *
 * The predicted edit is rendered as a highlighted decoration at the target
 * location, matching Cursor's NES UX:
 *   - The target line is dimmed/underlined.
 *   - Ghost text shows the replacement inline.
 *   - Tab accepts, Escape dismisses.
 */
export class NesProvider {
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private debounceMs: number;

  /** Rolling edit history — the last N document changes. */
  private editHistory: EditDelta[] = [];
  private readonly maxHistoryLen = 8;

  /** The currently displayed hint and the editor it targets. */
  private activeHint: NesHint | null = null;
  private activeEditor: vscode.TextEditor | null = null;

  private readonly decorationType: vscode.TextEditorDecorationType;
  private readonly statusBarItem: vscode.StatusBarItem;

  constructor(private readonly context: vscode.ExtensionContext) {
    const cfg = vscode.workspace.getConfiguration("oxidecode.nes");
    this.debounceMs = cfg.get<number>("debounceMs", 300);

    this.decorationType = vscode.window.createTextEditorDecorationType({
      after: {
        color: new vscode.ThemeColor("editorGhostText.foreground"),
        fontStyle: "italic",
      },
      backgroundColor: new vscode.ThemeColor(
        "diffEditor.insertedLineBackground"
      ),
      isWholeLine: false,
    });

    this.statusBarItem = vscode.window.createStatusBarItem(
      vscode.StatusBarAlignment.Right,
      99
    );
    this.statusBarItem.text = "$(sparkle) NES";
    this.statusBarItem.tooltip = "OxideCode Next Edit Suggestion active";

    context.subscriptions.push(this.decorationType, this.statusBarItem);
  }

  /** Called by the extension on every document change event. */
  onDocumentChange(event: vscode.TextDocumentChangeEvent): void {
    const enabled = vscode.workspace
      .getConfiguration("oxidecode.nes")
      .get<boolean>("enabled", true);
    if (!enabled || event.contentChanges.length === 0) return;

    const document = event.document;
    const now = Date.now();

    for (const change of event.contentChanges) {
      const delta: EditDelta = {
        filepath: vscode.workspace.asRelativePath(document.uri),
        startLine: change.range.start.line,
        startCol: change.range.start.character,
        removed: change.rangeLength > 0 ? document.getText(change.range) : "",
        inserted: change.text,
        fileContent: document.getText(),
        timestampMs: now,
      };

      if (this.editHistory.length >= this.maxHistoryLen) {
        this.editHistory.shift();
      }
      this.editHistory.push(delta);
    }

    this.dismissHint();
    this.schedulePrediction(document);
  }

  /** Schedule a NES prediction after the debounce delay. */
  private schedulePrediction(document: vscode.TextDocument): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
    }

    this.debounceTimer = setTimeout(() => {
      const editor = vscode.window.activeTextEditor;
      if (!editor || editor.document !== document) return;
      this.requestPrediction(editor);
    }, this.debounceMs);
  }

  private async requestPrediction(
    editor: vscode.TextEditor
  ): Promise<void> {
    const document = editor.document;
    const cursor = editor.selection.active;
    const providerConfig = getProviderConfig();

    try {
      const hint = await predictNextEdit(
        providerConfig,
        [...this.editHistory],
        vscode.workspace.asRelativePath(document.uri),
        cursor.line,
        cursor.character,
        document.getText(),
        document.languageId
      );

      if (!hint) return;

      // Only display if the hint targets the currently active file.
      const activeUri = editor.document.uri;
      const hintUri = vscode.Uri.joinPath(
        vscode.workspace.workspaceFolders?.[0]?.uri ?? activeUri,
        hint.filepath
      );

      if (hintUri.fsPath !== activeUri.fsPath) {
        // Cross-file NES: open the target file and display there.
        const targetDoc = await vscode.workspace.openTextDocument(hintUri);
        const targetEditor = await vscode.window.showTextDocument(
          targetDoc,
          { preview: true, preserveFocus: true }
        );
        this.showHint(targetEditor, hint);
      } else {
        this.showHint(editor, hint);
      }
    } catch (err) {
      console.error("[OxideCode] NES prediction error:", err);
    }
  }

  private showHint(editor: vscode.TextEditor, hint: NesHint): void {
    this.activeHint = hint;
    this.activeEditor = editor;

    const hintLine = Math.min(hint.line, editor.document.lineCount - 1);
    const lineLength = editor.document.lineAt(hintLine).text.length;
    const hintCol = Math.min(hint.col, lineLength);

    const position = new vscode.Position(hintLine, hintCol);
    const range = new vscode.Range(position, position);

    editor.setDecorations(this.decorationType, [
      {
        range,
        renderOptions: {
          after: {
            contentText: hint.replacement,
          },
        },
      },
    ]);

    // Scroll the target editor to the hint position.
    editor.revealRange(range, vscode.TextEditorRevealType.InCenterIfOutsideViewport);

    vscode.commands.executeCommand(
      "setContext",
      "oxidecode.nesVisible",
      true
    );
    this.statusBarItem.show();
  }

  /** Accept the active NES hint — applies the replacement text. */
  acceptHint(): void {
    if (!this.activeHint || !this.activeEditor) return;

    const hint = this.activeHint;
    const editor = this.activeEditor;

    editor.edit((editBuilder) => {
      if (hint.removeStartLine !== undefined) {
        const removeRange = new vscode.Range(
          hint.removeStartLine,
          hint.removeStartCol ?? 0,
          hint.removeEndLine ?? hint.removeStartLine,
          hint.removeEndCol ?? 0
        );
        editBuilder.replace(removeRange, hint.replacement);
      } else {
        const pos = new vscode.Position(hint.line, hint.col);
        editBuilder.insert(pos, hint.replacement);
      }
    });

    this.dismissHint();
  }

  /** Dismiss the active NES hint without applying it. */
  dismissHint(): void {
    this.activeHint = null;
    if (this.activeEditor) {
      this.activeEditor.setDecorations(this.decorationType, []);
    }
    this.activeEditor = null;
    vscode.commands.executeCommand("setContext", "oxidecode.nesVisible", false);
    this.statusBarItem.hide();
  }

  clearHistory(): void {
    this.editHistory = [];
    this.dismissHint();
  }
}
