/**
 * Native bridge to the Rust core via NAPI bindings.
 *
 * This module provides TypeScript wrappers around the native NAPI functions
 * exported by the oxidecode-node crate. It handles:
 * - Dynamic loading of the native module
 * - Graceful fallback when native module is unavailable
 * - Type-safe interfaces matching the Rust types
 */

import type {
	CompletionEndpoint,
	PromptStyle,
	ProviderConfig,
} from "~/core/config.ts";

// ─── Native Module Types ─────────────────────────────────────────────────────

/**
 * Provider configuration passed to native functions.
 * Maps to `JsProviderConfig` in the Rust NAPI bindings.
 */
export interface NativeProviderConfig {
	baseUrl: string;
	apiKey: string | undefined;
	model: string;
	completionModel: string | undefined;
	completionEndpoint: string | undefined;
}

/**
 * Completion context for FIM (Fill-In-Middle) requests.
 * Maps to `JsCompletionContext` in the Rust NAPI bindings.
 */
export interface NativeCompletionContext {
	prefix: string;
	suffix: string;
	language: string;
	filepath: string;
	promptStyle: string | undefined;
}

/**
 * NES configuration.
 * Maps to `JsNesConfig` in the Rust NAPI bindings.
 */
export interface NativeNesConfig {
	promptStyle: string | undefined;
	calibrationLogDir: string | undefined;
}

/**
 * Edit delta representing a single document change.
 * Maps to `JsEditDelta` in the Rust NAPI bindings.
 */
export interface NativeEditDelta {
	filepath: string;
	startLine: number;
	startCol: number;
	removed: string;
	inserted: string;
	fileContent: string;
	timestampMs: number;
}

/**
 * NES hint result from prediction.
 * Maps to `JsNesHint` in the Rust NAPI bindings.
 */
export interface NativeNesHint {
	filepath: string;
	line: number;
	col: number;
	replacement: string;
	removeStartLine: number | undefined;
	removeStartCol: number | undefined;
	removeEndLine: number | undefined;
	removeEndCol: number | undefined;
	confidence: number | undefined;
}

/**
 * Interface for the native module exports.
 */
interface NativeModule {
	initLogging(): void;
	cancelRequest(requestId: string): void;
	getCompletion(
		providerConfig: NativeProviderConfig,
		ctx: NativeCompletionContext,
	): Promise<string | null>;
	predictNextEdit(
		providerConfig: NativeProviderConfig,
		nesConfig: NativeNesConfig,
		deltas: NativeEditDelta[],
		cursorFilepath: string,
		cursorLine: number,
		cursorCol: number,
		fileContent: string,
		language: string,
		originalFileContent: string | undefined,
		requestId: string,
	): Promise<NativeNesHint | null>;
}

// ─── Module Loading ──────────────────────────────────────────────────────────

let nativeModule: NativeModule | null = null;
let loadError: Error | null = null;
let initialized = false;
let extensionPath: string | null = null;

/**
 * Sets the extension path for native module loading.
 * Must be called during extension activation with context.extensionPath.
 */
export function setExtensionPath(path: string): void {
	extensionPath = path;
}

/**
 * Loads the native module from various possible paths.
 * Throws if the module cannot be loaded from any path.
 */
function loadNativeModuleFromPaths(): NativeModule {
	const platform = process.platform;
	const arch = process.arch;
	const moduleName = `oxidecode.${platform}-${arch}.node`;

	const path = require("node:path");

	// Use extension path if set, otherwise try to determine from module location
	let searchPaths: string[] = [];

	if (extensionPath) {
		searchPaths.push(extensionPath);
	}

	// Also try using the module's actual location (for development)
	// module.path points to the directory containing the module
	if (typeof module !== "undefined" && module.path) {
		searchPaths.push(path.dirname(module.path));
		searchPaths.push(path.join(path.dirname(module.path), ".."));
	}

	// Try each search path
	for (const basePath of searchPaths) {
		// Try platform-specific path first (e.g., oxidecode.win32-x64.node)
		try {
			const platformPath = path.join(basePath, moduleName);
			console.log(`[OxideCode] Trying to load native module from: ${platformPath}`);
			return require(platformPath) as NativeModule;
		} catch {
			// Try generic path as fallback
			try {
				const genericPath = path.join(basePath, "oxidecode.node");
				console.log(`[OxideCode] Trying to load native module from: ${genericPath}`);
				return require(genericPath) as NativeModule;
			} catch {
				// Continue to next search path
			}
		}
	}

	throw new Error(
		`Cannot find native module. Searched in: ${searchPaths.join(", ")}. ` +
		`Looking for: ${moduleName} or oxidecode.node`
	);
}

/**
 * Attempts to load the native module.
 * Returns null if the module is not available.
 */
function tryLoadNativeModule(): NativeModule | null {
	if (initialized) {
		return nativeModule;
	}
	initialized = true;

	try {
		// The native module is built to the extension root as oxidecode.node
		const mod = loadNativeModuleFromPaths();

		nativeModule = mod;
		console.log("[OxideCode] Native module loaded successfully");

		// Initialize tracing
		try {
			mod.initLogging();
		} catch (e) {
			// Logging init may fail if already initialized, that's OK
			console.log("[OxideCode] Tracing init:", e);
		}

		return nativeModule;
	} catch (e) {
		loadError = e instanceof Error ? e : new Error(String(e));
		console.warn(
			"[OxideCode] Native module not available, falling back to HTTP:",
			loadError.message,
		);
		return null;
	}
}

/**
 * Returns whether the native module is available.
 */
export function isNativeAvailable(): boolean {
	return tryLoadNativeModule() !== null;
}

/**
 * Returns the error that occurred when loading the native module, if any.
 */
export function getNativeLoadError(): Error | null {
	tryLoadNativeModule();
	return loadError;
}

// ─── Helper Functions ────────────────────────────────────────────────────────

/**
 * Converts ProviderConfig to NativeProviderConfig.
 */
export function toNativeProviderConfig(
	provider: ProviderConfig,
	completionEndpoint: CompletionEndpoint,
): NativeProviderConfig {
	return {
		baseUrl: provider.baseUrl,
		apiKey: provider.apiKey,
		model: provider.model,
		completionModel: provider.completionModel,
		completionEndpoint:
			completionEndpoint === "chat_completions"
				? "chat_completions"
				: "completions",
	};
}

/**
 * Converts PromptStyle to the string format expected by the native module.
 */
export function toNativePromptStyle(style: PromptStyle): string {
	return style; // Already matches: "generic" | "sweep" | "zeta1" | "zeta2"
}

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Gets a FIM completion from the native core.
 * Returns null if the native module is not available or the request fails.
 */
export async function nativeGetCompletion(
	provider: ProviderConfig,
	completionEndpoint: CompletionEndpoint,
	promptStyle: PromptStyle,
	prefix: string,
	suffix: string,
	language: string,
	filepath: string,
): Promise<string | null> {
	const mod = tryLoadNativeModule();
	if (!mod) {
		return null;
	}

	const providerConfig = toNativeProviderConfig(provider, completionEndpoint);
	const ctx: NativeCompletionContext = {
		prefix,
		suffix,
		language,
		filepath,
		promptStyle: toNativePromptStyle(promptStyle),
	};

	try {
		return await mod.getCompletion(providerConfig, ctx);
	} catch (e) {
		console.error("[OxideCode] Native getCompletion failed:", e);
		return null;
	}
}

/**
 * Predicts the next edit using the native NES engine.
 * Returns null if the native module is not available or the request fails.
 */
export async function nativePredictNextEdit(
	provider: ProviderConfig,
	completionEndpoint: CompletionEndpoint,
	promptStyle: PromptStyle,
	deltas: NativeEditDelta[],
	cursorFilepath: string,
	cursorLine: number,
	cursorCol: number,
	fileContent: string,
	language: string,
	originalFileContent: string | undefined,
	requestId: string,
	calibrationLogDir?: string,
): Promise<NativeNesHint | null> {
	const mod = tryLoadNativeModule();
	if (!mod) {
		return null;
	}

	const providerConfig = toNativeProviderConfig(provider, completionEndpoint);
	const nesConfig: NativeNesConfig = {
		promptStyle: toNativePromptStyle(promptStyle),
		calibrationLogDir,
	};

	try {
		return await mod.predictNextEdit(
			providerConfig,
			nesConfig,
			deltas,
			cursorFilepath,
			cursorLine,
			cursorCol,
			fileContent,
			language,
			originalFileContent,
			requestId,
		);
	} catch (e) {
		console.error("[OxideCode] Native predictNextEdit failed:", e);
		return null;
	}
}

export function cancelNativeRequest(requestId: string): void {
	const mod = tryLoadNativeModule();
	if (!mod) return;

	try {
		mod.cancelRequest(requestId);
	} catch (e) {
		console.error("[OxideCode] Native cancelRequest failed:", e);
	}
}

/**
 * Converts a NativeNesHint to the AutocompleteResult format used by the extension.
 */
export function nesHintToAutocompleteResult(
	hint: NativeNesHint,
	documentText: string,
): {
	id: string;
	startIndex: number;
	endIndex: number;
	completion: string;
	confidence: number;
} | null {
	if (!hint.replacement) {
		return null;
	}

	// Calculate character offsets from line/col
	const lines = documentText.split("\n");
	let startIndex = 0;

	// Calculate start position
	if (hint.removeStartLine !== undefined && hint.removeStartCol !== undefined) {
		for (let i = 0; i < hint.removeStartLine; i++) {
			startIndex += (lines[i]?.length ?? 0) + 1; // +1 for newline
		}
		startIndex += hint.removeStartCol;
	} else {
		// Use hint position as start
		for (let i = 0; i < hint.line; i++) {
			startIndex += (lines[i]?.length ?? 0) + 1;
		}
		startIndex += hint.col;
	}

	// Calculate end position
	let endIndex = startIndex;
	if (hint.removeEndLine !== undefined && hint.removeEndCol !== undefined) {
		endIndex = 0;
		for (let i = 0; i < hint.removeEndLine; i++) {
			endIndex += (lines[i]?.length ?? 0) + 1;
		}
		endIndex += hint.removeEndCol;
	}

	return {
		id: `nes-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
		startIndex,
		endIndex,
		completion: hint.replacement,
		confidence: hint.confidence ?? 0.5,
	};
}
