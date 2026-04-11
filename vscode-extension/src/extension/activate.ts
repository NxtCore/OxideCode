import * as vscode from "vscode";

import { ApiClient } from "~/api/client.ts";
import { config } from "~/core/config.ts";
import {
	getNativeLoadError,
	isNativeAvailable,
	setExtensionPath,
} from "~/core/native-bridge.ts";
import { InlineEditProvider } from "~/editor/inline-edit-provider.ts";
import { JumpEditManager } from "~/editor/jump-edit-manager.ts";
import {
	initSyntaxHighlighter,
	reloadTheme,
} from "~/editor/syntax-highlight-renderer.ts";
import {
	registerStatusBarCommands,
	SweepStatusBar,
} from "~/extension/status-bar.ts";
import { LocalAutocompleteServer } from "~/services/local-server.ts";
import {
	type AutocompleteMetricsPayload,
	AutocompleteMetricsTracker,
} from "~/telemetry/autocomplete-metrics.ts";
import { DocumentTracker } from "~/telemetry/document-tracker.ts";

let tracker: DocumentTracker;
let jumpEditManager: JumpEditManager;
let provider: InlineEditProvider;
let statusBar: SweepStatusBar;
let metricsTracker: AutocompleteMetricsTracker;
let localServer: LocalAutocompleteServer | null = null;

export function activate(context: vscode.ExtensionContext) {
	// Set the extension path FIRST so native module can be found
	setExtensionPath(context.extensionPath);
	console.log(`[OxideCode] Extension path: ${context.extensionPath}`);

	initSyntaxHighlighter();

	// Log startup information
	const backend = config.backend;
	const nativeAvailable = isNativeAvailable();
	const nativeError = getNativeLoadError();
	const effectiveBackend =
		backend === "native" && !nativeAvailable ? "http" : backend;

	console.log(`[OxideCode] Starting with backend: ${backend}`);
	console.log(`[OxideCode] Native module available: ${nativeAvailable}`);
	if (nativeError) {
		console.log(`[OxideCode] Native load error: ${nativeError.message}`);
	}
	console.log(`[OxideCode] Effective backend: ${effectiveBackend}`);
	console.log(`[OxideCode] Prompt style: ${config.promptStyle}`);
	console.log(`[OxideCode] Completion endpoint: ${config.completionEndpoint}`);
	console.log(`[OxideCode] Provider base URL: ${config.provider.baseUrl}`);
	console.log(`[OxideCode] Provider model: ${config.provider.model}`);

	tracker = new DocumentTracker();
	metricsTracker = new AutocompleteMetricsTracker();
	jumpEditManager = new JumpEditManager(metricsTracker);

	// Only create local server if needed (HTTP backend or native fallback)
	if (effectiveBackend === "http") {
		localServer = new LocalAutocompleteServer();
	}

	const apiClient = new ApiClient(localServer ?? undefined, tracker);

	provider = new InlineEditProvider(
		tracker,
		jumpEditManager,
		apiClient,
		metricsTracker,
	);

	const refreshTheme = () => {
		reloadTheme();
		jumpEditManager.refreshJumpEditDecorations();
	};

	const providerDisposable =
		vscode.languages.registerInlineCompletionItemProvider(
			{ pattern: "**/*" },
			provider,
		);

	const triggerCommand = vscode.commands.registerCommand(
		"sweep.triggerNextEdit",
		() => {
			vscode.commands.executeCommand("editor.action.inlineEdit.trigger");
		},
	);

	const acceptJumpEditCommand = vscode.commands.registerCommand(
		"sweep.acceptJumpEdit",
		() => jumpEditManager.acceptJumpEdit(),
	);

	const acceptInlineEditCommand = vscode.commands.registerCommand(
		"sweep.acceptInlineEdit",
		(
			payload: AutocompleteMetricsPayload | undefined,
			acceptedSuggestion:
				| {
						id: string;
						startIndex: number;
						endIndex: number;
						completion: string;
				  }
				| undefined,
		) => {
			if (!payload) return;
			provider.handleInlineAccept(payload, acceptedSuggestion);
			metricsTracker.trackAccepted(payload);
		},
	);

	const dismissJumpEditCommand = vscode.commands.registerCommand(
		"sweep.dismissJumpEdit",
		() => jumpEditManager.dismissJumpEdit(),
	);

	statusBar = new SweepStatusBar(context);
	const statusBarCommands = registerStatusBarCommands(
		context,
		localServer ?? new LocalAutocompleteServer(), // Temporary server instance for status bar commands
	);

	const changeListener = vscode.workspace.onDidChangeTextDocument((event) => {
		if (event.document === vscode.window.activeTextEditor?.document) {
			tracker.trackChange(event);
		}
	});

	const themeChangeListener = vscode.window.onDidChangeActiveColorTheme(() => {
		refreshTheme();
	});
	const themeConfigListener = vscode.workspace.onDidChangeConfiguration(
		(event) => {
			if (!event.affectsConfiguration("workbench.colorTheme")) return;
			// The colorTheme setting can update slightly after the active theme event.
			setTimeout(() => {
				refreshTheme();
			}, 0);
		},
	);

	// Watch for OxideCode configuration changes
	const configChangeListener = vscode.workspace.onDidChangeConfiguration(
		(event) => {
			if (!event.affectsConfiguration("oxidecode")) return;

			console.log("[OxideCode] Configuration changed, reloading...");
			console.log(`[OxideCode] New backend: ${config.backend}`);
			console.log(`[OxideCode] New prompt style: ${config.promptStyle}`);
			console.log(
				`[OxideCode] New completion endpoint: ${config.completionEndpoint}`,
			);

			// Note: Changing backend mode requires extension reload for full effect
			// The API client will pick up the new settings on the next request
		},
	);

	const handleCursorMove = (editor: vscode.TextEditor): void => {
		void provider.handleCursorMove(editor.document, editor.selection.active);
		jumpEditManager.handleCursorMove(editor.selection.active);
	};

	const editorChangeListener = vscode.window.onDidChangeActiveTextEditor(
		(editor) => {
			if (editor) {
				tracker.trackFileVisit(editor.document);
				handleCursorMove(editor);
			}
		},
	);

	const selectionChangeListener = vscode.window.onDidChangeTextEditorSelection(
		(event) => {
			if (event.textEditor === vscode.window.activeTextEditor) {
				tracker.trackSelectionChange(
					event.textEditor.document,
					event.selections,
				);
				for (const selection of event.selections) {
					tracker.trackCursorMovement(
						event.textEditor.document,
						selection.active,
					);
				}
				handleCursorMove(event.textEditor);
			}
		},
	);

	if (vscode.window.activeTextEditor) {
		tracker.trackFileVisit(vscode.window.activeTextEditor.document);
		handleCursorMove(vscode.window.activeTextEditor);
	}

	const subscriptions: vscode.Disposable[] = [
		providerDisposable,
		triggerCommand,
		acceptJumpEditCommand,
		acceptInlineEditCommand,
		dismissJumpEditCommand,
		changeListener,
		editorChangeListener,
		selectionChangeListener,
		themeChangeListener,
		themeConfigListener,
		configChangeListener,
		tracker,
		jumpEditManager,
		metricsTracker,
		statusBar,
		...statusBarCommands,
	];

	// Add local server to subscriptions if it exists
	if (localServer) {
		subscriptions.push(localServer);
	}

	context.subscriptions.push(...subscriptions);

	// Auto-start local server only when using HTTP backend
	if (effectiveBackend === "http" && localServer) {
		localServer.ensureServerRunning().catch((error) => {
			console.error("[OxideCode] Failed to auto-start local server:", error);
		});
	}

	// Show info message about backend mode on first activation
	if (effectiveBackend === "native") {
		console.log("[OxideCode] Using native Rust core for inference");
	} else if (backend === "native" && !nativeAvailable) {
		vscode.window.showWarningMessage(
			`OxideCode: Native backend unavailable (${nativeError?.message ?? "unknown error"}), falling back to HTTP server.`,
		);
	}
}

export function deactivate() {}
