import * as path from "node:path";
import * as vscode from "vscode";

import { DEFAULT_MAX_CONTEXT_FILES } from "~/core/constants.ts";

const CONFIG_SECTION = "oxidecode";

/**
 * Backend mode for inference requests.
 * - `native`: Use Rust core via NAPI bindings (faster, direct).
 * - `http`: Use HTTP local server (requires Python/uvx).
 */
export type BackendMode = "native" | "http";

/**
 * Prompt style for NES (Next-Edit Suggestion) requests.
 * Maps to `NesPromptStyle` in the Rust core.
 */
export type PromptStyle = "generic" | "sweep" | "zeta1" | "zeta2";

/**
 * HTTP endpoint type for inference requests.
 * - `completions`: Use `/v1/completions` (raw text, avoids chat-template framing).
 * - `chat_completions`: Use `/v1/chat/completions` (chat format).
 */
export type CompletionEndpoint = "completions" | "chat_completions";

/**
 * Provider configuration for the inference server.
 */
export interface ProviderConfig {
	baseUrl: string;
	apiKey: string | undefined;
	model: string;
	completionModel: string | undefined;
}

export class OxideConfig {
	private get config(): vscode.WorkspaceConfiguration {
		return vscode.workspace.getConfiguration(CONFIG_SECTION);
	}

	get enabled(): boolean {
		return this.config.get<boolean>("enabled", true);
	}

	/**
	 * Backend mode: "native" (Rust NAPI) or "http" (local Python server).
	 */
	get backend(): BackendMode {
		return this.config.get<BackendMode>("backend", "native");
	}

	/**
	 * Prompt style for NES requests: "generic", "sweep", "zeta1", "zeta2".
	 */
	get promptStyle(): PromptStyle {
		return this.config.get<PromptStyle>("promptStyle", "sweep");
	}

	/**
	 * HTTP endpoint type: "completions" or "chat_completions".
	 */
	get completionEndpoint(): CompletionEndpoint {
		return this.config.get<CompletionEndpoint>(
			"completionEndpoint",
			"completions",
		);
	}

	/**
	 * Provider configuration for the inference server.
	 */
	get provider(): ProviderConfig {
		const baseUrl = this.config.get<string>(
			"provider.baseUrl",
			"http://localhost:11434",
		);
		const apiKey = this.config.get<string>("provider.apiKey", "") || undefined;
		const model = this.config.get<string>("provider.model", "qwen2.5-coder:7b");
		const completionModel =
			this.config.get<string>("provider.completionModel", "") || undefined;

		return { baseUrl, apiKey, model, completionModel };
	}

	get maxContextFiles(): number {
		return this.config.get<number>(
			"maxContextFiles",
			DEFAULT_MAX_CONTEXT_FILES,
		);
	}

	get autocompleteExclusionPatterns(): string[] {
		return this.config.get<string[]>("autocompleteExclusionPatterns", []);
	}

	get autocompleteSnoozeUntil(): number {
		return this.config.get<number>("autocompleteSnoozeUntil", 0);
	}

	get localPort(): number {
		return this.config.get<number>("localPort", 8081);
	}

	isAutocompleteSnoozed(now = Date.now()): boolean {
		const snoozeUntil = this.autocompleteSnoozeUntil;
		return snoozeUntil > now;
	}

	getAutocompleteSnoozeRemainingMs(now = Date.now()): number | null {
		const snoozeUntil = this.autocompleteSnoozeUntil;
		if (!snoozeUntil) return null;
		return Math.max(0, snoozeUntil - now);
	}

	shouldExcludeFromAutocomplete(filePath: string): boolean {
		const patterns = this.autocompleteExclusionPatterns.filter(Boolean);
		if (patterns.length === 0) return false;
		const fileName = path.basename(filePath);
		const normalizedPath = filePath.replace(/\\/g, "/");
		return patterns.some((pattern) => {
			const trimmed = pattern.trim();
			if (!trimmed) return false;
			if (trimmed.includes("*")) {
				const regex = globToRegex(trimmed);
				return regex.test(normalizedPath);
			}
			return fileName.endsWith(trimmed) || normalizedPath.endsWith(trimmed);
		});
	}

	inspect<T>(key: string) {
		return this.config.inspect<T>(key);
	}

	setEnabled(
		value: boolean,
		target: vscode.ConfigurationTarget = this.getWorkspaceTarget(),
	): Thenable<void> {
		return this.config.update("enabled", value, target);
	}

	setBackend(
		value: BackendMode,
		target: vscode.ConfigurationTarget = vscode.ConfigurationTarget.Global,
	): Thenable<void> {
		return this.config.update("backend", value, target);
	}

	setPromptStyle(
		value: PromptStyle,
		target: vscode.ConfigurationTarget = vscode.ConfigurationTarget.Global,
	): Thenable<void> {
		return this.config.update("promptStyle", value, target);
	}

	setCompletionEndpoint(
		value: CompletionEndpoint,
		target: vscode.ConfigurationTarget = vscode.ConfigurationTarget.Global,
	): Thenable<void> {
		return this.config.update("completionEndpoint", value, target);
	}

	setAutocompleteSnoozeUntil(
		value: number,
		target: vscode.ConfigurationTarget = vscode.ConfigurationTarget.Global,
	): Thenable<void> {
		return this.config.update("autocompleteSnoozeUntil", value, target);
	}

	setLocalPort(
		value: number,
		target: vscode.ConfigurationTarget = vscode.ConfigurationTarget.Global,
	): Thenable<void> {
		return this.config.update("localPort", value, target);
	}

	private getWorkspaceTarget(): vscode.ConfigurationTarget {
		return vscode.workspace.workspaceFolders
			? vscode.ConfigurationTarget.Workspace
			: vscode.ConfigurationTarget.Global;
	}
}

export const config = new OxideConfig();

// Legacy alias for backwards compatibility
export { OxideConfig as SweepConfig };

function globToRegex(pattern: string): RegExp {
	const escaped = pattern.replace(/[.+^${}()|[\]\\]/g, "\\$&");
	const placeholder = "__DOUBLE_STAR__";
	const withPlaceholder = escaped.replace(/\*\*/g, placeholder);
	const withStar = withPlaceholder.replace(/\*/g, "[^/]*");
	const withDoubleStar = withStar.replace(new RegExp(placeholder, "g"), ".*");
	return new RegExp(`^${withDoubleStar}$`);
}
