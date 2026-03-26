<script lang="ts">
  import { onMount } from "svelte";
  import { sendToHost, onHostMessage } from "./bridge/host";

  interface Message {
    role: "user" | "assistant";
    content: string;
  }

  let messages: Message[] = [];
  let inputValue = "";
  let isLoading = false;
  let messagesEl: HTMLElement;

  onMount(() => {
    const unsub = onHostMessage((raw) => {
      const msg = raw as { type: string; token?: string; done?: boolean };
      if (msg.type === "token" && msg.token) {
        if (messages.at(-1)?.role === "assistant") {
          messages[messages.length - 1].content += msg.token;
          messages = messages;
        } else {
          messages = [...messages, { role: "assistant", content: msg.token }];
        }
        scrollToBottom();
      }
      if (msg.type === "done") {
        isLoading = false;
      }
    });
    return unsub;
  });

  function send() {
    const text = inputValue.trim();
    if (!text || isLoading) return;
    messages = [...messages, { role: "user", content: text }];
    inputValue = "";
    isLoading = true;
    sendToHost({ type: "chat", content: text });
    scrollToBottom();
  }

  function scrollToBottom() {
    setTimeout(() => {
      messagesEl?.scrollTo({ top: messagesEl.scrollHeight, behavior: "smooth" });
    }, 10);
  }

  function onKeydown(e: KeyboardEvent) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }
</script>

<main>
  <div class="messages" bind:this={messagesEl}>
    {#each messages as msg}
      <div class="message {msg.role}">
        <pre>{msg.content}</pre>
      </div>
    {/each}
    {#if isLoading}
      <div class="message assistant loading">
        <span class="dot" />
        <span class="dot" />
        <span class="dot" />
      </div>
    {/if}
  </div>

  <div class="input-bar">
    <textarea
      bind:value={inputValue}
      on:keydown={onKeydown}
      placeholder="Ask OxideCode…"
      rows={3}
      disabled={isLoading}
    />
    <button on:click={send} disabled={isLoading || !inputValue.trim()}>
      Send
    </button>
  </div>
</main>

<style>
  :global(body) {
    margin: 0;
    font-family: var(--vscode-font-family, system-ui, sans-serif);
    font-size: var(--vscode-font-size, 13px);
    background: var(--vscode-sideBar-background, #1e1e1e);
    color: var(--vscode-foreground, #ccc);
  }

  main {
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow: hidden;
  }

  .messages {
    flex: 1;
    overflow-y: auto;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .message {
    max-width: 90%;
    padding: 8px 12px;
    border-radius: 6px;
    word-break: break-word;
  }

  .message.user {
    align-self: flex-end;
    background: var(--vscode-button-background, #0e639c);
    color: var(--vscode-button-foreground, #fff);
  }

  .message.assistant {
    align-self: flex-start;
    background: var(--vscode-editor-inactiveSelectionBackground, #2d2d2d);
  }

  .message pre {
    margin: 0;
    white-space: pre-wrap;
    font-family: inherit;
  }

  .loading {
    display: flex;
    gap: 4px;
    align-items: center;
    padding: 12px;
  }

  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
    animation: pulse 1.2s infinite;
  }

  .dot:nth-child(2) { animation-delay: 0.2s; }
  .dot:nth-child(3) { animation-delay: 0.4s; }

  @keyframes pulse {
    0%, 100% { opacity: 0.3; }
    50% { opacity: 1; }
  }

  .input-bar {
    display: flex;
    gap: 8px;
    padding: 8px 12px;
    border-top: 1px solid var(--vscode-panel-border, #333);
    background: var(--vscode-editor-background, #1e1e1e);
  }

  textarea {
    flex: 1;
    resize: none;
    background: var(--vscode-input-background, #3c3c3c);
    color: var(--vscode-input-foreground, #ccc);
    border: 1px solid var(--vscode-input-border, #555);
    border-radius: 4px;
    padding: 6px 8px;
    font-family: inherit;
    font-size: inherit;
  }

  button {
    padding: 6px 14px;
    background: var(--vscode-button-background, #0e639c);
    color: var(--vscode-button-foreground, #fff);
    border: none;
    border-radius: 4px;
    cursor: pointer;
    align-self: flex-end;
  }

  button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
</style>
