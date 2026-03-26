/**
 * IDE-agnostic postMessage bridge.
 *
 * Both VSCode and IntelliJ JCEF expose a way to send messages to the
 * host IDE and receive messages back. This module abstracts that.
 */

type MessageHandler = (msg: unknown) => void;
const handlers: MessageHandler[] = [];

// VSCode webview API — injected by the VSCode runtime when running inside a WebviewPanel.
declare function acquireVsCodeApi(): {
  postMessage(msg: unknown): void;
  getState(): unknown;
  setState(state: unknown): void;
};

let vscodeApi: ReturnType<typeof acquireVsCodeApi> | null = null;

function isVSCode(): boolean {
  return typeof acquireVsCodeApi !== "undefined";
}

export function sendToHost(msg: unknown): void {
  if (isVSCode()) {
    vscodeApi ??= acquireVsCodeApi();
    vscodeApi.postMessage(msg);
  } else {
    // IntelliJ JCEF — the host registers a handler under `window.oxidecodeHost`
    (window as unknown as { oxidecodeHost?: { postMessage(s: string): void } })
      .oxidecodeHost?.postMessage(JSON.stringify(msg));
  }
}

export function onHostMessage(handler: MessageHandler): () => void {
  handlers.push(handler);
  return () => {
    const i = handlers.indexOf(handler);
    if (i !== -1) handlers.splice(i, 1);
  };
}

// Listen for incoming messages from the host.
window.addEventListener("message", (event) => {
  for (const h of handlers) {
    h(event.data);
  }
});
