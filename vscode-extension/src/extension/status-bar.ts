import * as vscode from "vscode";

import { type BackendMode, config, type PromptStyle } from "~/core/config.ts";
import { isNativeAvailable } from "~/core/native-bridge.ts";
import type { LocalAutocompleteServer } from "~/services/local-server.ts";

export class SweepStatusBar implements vscode.Disposable {
	private statusBarItem: vscode.StatusBarItem;
	private disposables: vscode.Disposable[] = [];

	constructor(_context: vscode.ExtensionContext) {
		this.statusBarItem = vscode.window.createStatusBarItem(
			vscode.StatusBarAlignment.Right,
			100,
		);
		this.statusBarItem.command = "sweep.showMenu";
		this.updateStatusBar();

		this.disposables.push(
			vscode.workspace.onDidChangeConfiguration((e) => {
				if (
					e.affectsConfiguration("oxidecode.enabled") ||
					e.affectsConfiguration("oxidecode.autocompleteSnoozeUntil") ||
					e.affectsConfiguration("oxidecode.backend") ||
					e.affectsConfiguration("oxidecode.promptStyle")
				) {
					this.updateStatusBar();
				}
			}),
		);

		this.statusBarItem.show();
	}

	private updateStatusBar(): void {
		const isEnabled = config.enabled;
		const isSnoozed = config.isAutocompleteSnoozed();
		const backend = this.getEffectiveBackend();
		const promptStyle = config.promptStyle;

		// Show backend indicator in status bar text
		const backendIcon = backend === "native" ? "$(zap)" : "$(server)";
		this.statusBarItem.text = `${backendIcon} OxideCode`;
		this.statusBarItem.tooltip = this.buildTooltip(
			isEnabled,
			isSnoozed,
			backend,
			promptStyle,
		);

		if (!isEnabled || isSnoozed) {
			this.statusBarItem.backgroundColor = new vscode.ThemeColor(
				"statusBarItem.warningBackground",
			);
		} else {
			this.statusBarItem.backgroundColor = undefined;
		}
	}

	private getEffectiveBackend(): BackendMode {
		const preferredBackend = config.backend;
		if (preferredBackend === "native" && !isNativeAvailable()) {
			return "http";
		}
		return preferredBackend;
	}

	private buildTooltip(
		isEnabled: boolean,
		isSnoozed: boolean,
		backend: BackendMode,
		promptStyle: PromptStyle,
	): string {
		const status = isEnabled ? "Enabled" : "Disabled";
		const snoozeUntil = config.autocompleteSnoozeUntil;
		const snoozeLine = isSnoozed
			? `Snoozed Until: ${formatSnoozeTime(snoozeUntil)}`
			: "Snoozed: Off";

		const backendLabel = backend === "native" ? "Native (Rust)" : "HTTP Server";
		const promptStyleLabel = formatPromptStyle(promptStyle);

		return [
			"OxideCode",
			`Status: ${status}`,
			snoozeLine,
			"",
			`Backend: ${backendLabel}`,
			`Prompt Style: ${promptStyleLabel}`,
			`Model: ${config.provider.model}`,
			"",
			"Click to open menu",
		].join("\n");
	}

	dispose(): void {
		this.statusBarItem.dispose();
		for (const disposable of this.disposables) {
			disposable.dispose();
		}
	}
}

export function registerStatusBarCommands(
	_context: vscode.ExtensionContext,
	localServer?: LocalAutocompleteServer,
): vscode.Disposable[] {
	const disposables: vscode.Disposable[] = [];

	disposables.push(
		vscode.commands.registerCommand("sweep.showMenu", async () => {
			const isEnabled = config.enabled;
			const isSnoozed = config.isAutocompleteSnoozed();
			const backend = config.backend;
			const promptStyle = config.promptStyle;
			const nativeAvailable = isNativeAvailable();

			interface MenuItem extends vscode.QuickPickItem {
				action: string;
			}

			const items: MenuItem[] = [
				{
					label: `$(${isEnabled ? "check" : "circle-outline"}) Autocomplete`,
					description: isEnabled ? "Enabled" : "Disabled",
					action: "toggleEnabled",
				},
				{
					label: isSnoozed
						? "$(play-circle) Resume Autocomplete"
						: "$(clock) Snooze Autocomplete",
					description: isSnoozed
						? "Resume suggestions immediately"
						: "Pause suggestions temporarily",
					action: isSnoozed ? "resumeSnooze" : "snooze",
				},
				{
					label: "$(separator)",
					kind: vscode.QuickPickItemKind.Separator,
					action: "",
				},
				{
					label: `$(zap) Backend: ${backend === "native" ? "Native" : "HTTP"}`,
					description: nativeAvailable
						? "Click to switch"
						: "Native unavailable",
					action: nativeAvailable ? "switchBackend" : "",
				},
				{
					label: `$(symbol-method) Prompt Style: ${formatPromptStyle(promptStyle)}`,
					description: "Click to change",
					action: "switchPromptStyle",
				},
				{
					label: "$(separator)",
					kind: vscode.QuickPickItemKind.Separator,
					action: "",
				},
				{
					label: "$(server) Start Local Server",
					description: "Manually start the local HTTP server",
					action: "startLocalServer",
				},
				{
					label: "$(gear) Open Settings",
					description: "Configure OxideCode settings",
					action: "openSettings",
				},
			];

			const selection = await vscode.window.showQuickPick(
				items.filter((item) => item.action !== ""),
				{
					placeHolder: "OxideCode Settings",
					title: "OxideCode",
				},
			);

			if (selection) {
				switch (selection.action) {
					case "toggleEnabled":
						await vscode.commands.executeCommand("sweep.toggleEnabled");
						break;
					case "snooze":
						await handleSnooze();
						break;
					case "resumeSnooze":
						await handleResumeSnooze();
						break;
					case "switchBackend":
						await handleSwitchBackend();
						break;
					case "switchPromptStyle":
						await handleSwitchPromptStyle();
						break;
					case "startLocalServer":
						if (localServer) {
							try {
								await localServer.startServer();
								vscode.window.showInformationMessage(
									"OxideCode local server started.",
								);
							} catch (error) {
								vscode.window.showErrorMessage(
									`Failed to start local server: ${(error as Error).message}`,
								);
							}
						} else {
							vscode.window.showWarningMessage(
								"Local server is not available (using native backend).",
							);
						}
						break;
					case "openSettings":
						await vscode.commands.executeCommand(
							"workbench.action.openSettings",
							"oxidecode",
						);
						break;
				}
			}
		}),
	);

	disposables.push(
		vscode.commands.registerCommand("sweep.toggleEnabled", async () => {
			const inspection = config.inspect<boolean>("enabled");
			const current =
				inspection?.workspaceValue ??
				inspection?.globalValue ??
				inspection?.defaultValue ??
				true;
			await config.setEnabled(!current);

			// Hide any existing inline suggestions when disabling
			if (current) {
				await vscode.commands.executeCommand(
					"editor.action.inlineSuggest.hide",
				);
			}

			vscode.window.showInformationMessage(
				`OxideCode autocomplete ${!current ? "enabled" : "disabled"}`,
			);
		}),
	);

	return disposables;
}

function formatSnoozeTime(timestamp: number): string {
	return new Date(timestamp).toLocaleString();
}

function formatPromptStyle(style: PromptStyle): string {
	switch (style) {
		case "generic":
			return "Generic";
		case "sweep":
			return "Sweep";
		case "zeta1":
			return "Zeta v1";
		case "zeta2":
			return "Zeta v2";
		default:
			return style;
	}
}

async function handleSnooze(): Promise<void> {
	const options: Array<{ label: string; minutes: number }> = [
		{ label: "15 minutes", minutes: 15 },
		{ label: "30 minutes", minutes: 30 },
		{ label: "1 hour", minutes: 60 },
		{ label: "4 hours", minutes: 240 },
	];

	const selection = await vscode.window.showQuickPick(
		options.map((option) => ({
			label: option.label,
			description: `Pause autocomplete for ${option.label}`,
		})),
		{ title: "Snooze OxideCode Autocomplete", placeHolder: "Choose duration" },
	);

	if (!selection) return;

	const selected = options.find((option) => option.label === selection.label);
	if (!selected) return;

	const until = Date.now() + selected.minutes * 60 * 1000;
	await config.setAutocompleteSnoozeUntil(
		until,
		vscode.ConfigurationTarget.Global,
	);
	await vscode.commands.executeCommand("editor.action.inlineSuggest.hide");
	vscode.window.showInformationMessage(
		`OxideCode autocomplete snoozed until ${formatSnoozeTime(until)}.`,
	);
}

async function handleResumeSnooze(): Promise<void> {
	await config.setAutocompleteSnoozeUntil(0, vscode.ConfigurationTarget.Global);
	vscode.window.showInformationMessage("OxideCode autocomplete resumed.");
}

async function handleSwitchBackend(): Promise<void> {
	const currentBackend = config.backend;
	const nativeAvailable = isNativeAvailable();

	interface BackendOption extends vscode.QuickPickItem {
		value: BackendMode;
	}

	const options: BackendOption[] = [
		{
			label: "$(zap) Native (Rust)",
			description:
				currentBackend === "native"
					? "Currently selected"
					: nativeAvailable
						? "Faster, direct inference"
						: "Not available",
			value: "native",
			picked: currentBackend === "native",
		},
		{
			label: "$(server) HTTP Server",
			description:
				currentBackend === "http"
					? "Currently selected"
					: "Uses local Python server",
			value: "http",
			picked: currentBackend === "http",
		},
	];

	const selection = await vscode.window.showQuickPick(options, {
		title: "Select Backend",
		placeHolder: "Choose inference backend",
	});

	if (selection && selection.value !== currentBackend) {
		if (selection.value === "native" && !nativeAvailable) {
			vscode.window.showWarningMessage(
				"Native backend is not available. The native module could not be loaded.",
			);
			return;
		}
		await config.setBackend(selection.value);
		vscode.window.showInformationMessage(
			`OxideCode backend changed to ${selection.value === "native" ? "Native" : "HTTP"}.`,
		);
	}
}

async function handleSwitchPromptStyle(): Promise<void> {
	const currentStyle = config.promptStyle;

	interface StyleOption extends vscode.QuickPickItem {
		value: PromptStyle;
	}

	const options: StyleOption[] = [
		{
			label: "$(symbol-method) Sweep",
			description:
				currentStyle === "sweep"
					? "Currently selected"
					: "Sweep AI next-edit format",
			detail: "Best for sweep-next-edit-1.5b/7b models",
			value: "sweep",
			picked: currentStyle === "sweep",
		},
		{
			label: "$(symbol-method) Zeta v2",
			description:
				currentStyle === "zeta2"
					? "Currently selected"
					: "Zed's modern SPM FIM format",
			detail: "Best for zeta-2 / seed-coder models",
			value: "zeta2",
			picked: currentStyle === "zeta2",
		},
		{
			label: "$(symbol-method) Zeta v1",
			description:
				currentStyle === "zeta1"
					? "Currently selected"
					: "Zed's legacy instruction format",
			detail: "For older zeta v1 models",
			value: "zeta1",
			picked: currentStyle === "zeta1",
		},
		{
			label: "$(symbol-method) Generic",
			description:
				currentStyle === "generic"
					? "Currently selected"
					: "JSON-based prompt format",
			detail: "Generic format for custom models",
			value: "generic",
			picked: currentStyle === "generic",
		},
	];

	const selection = await vscode.window.showQuickPick(options, {
		title: "Select Prompt Style",
		placeHolder: "Choose prompt style for NES requests",
	});

	if (selection && selection.value !== currentStyle) {
		await config.setPromptStyle(selection.value);
		vscode.window.showInformationMessage(
			`OxideCode prompt style changed to ${formatPromptStyle(selection.value)}.`,
		);
	}
}
