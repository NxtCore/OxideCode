import { useEffect, useRef, useState } from "react";
import { sendToHost, onHostMessage } from "./bridge/host";

interface Message {
  role: "user" | "assistant";
  content: string;
}

export default function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const unsub = onHostMessage((raw) => {
      const msg = raw as { type: string; token?: string };
      if (msg.type === "token" && msg.token) {
        const token = msg.token;
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          if (last?.role === "assistant") {
            return [
              ...prev.slice(0, -1),
              { role: "assistant", content: last.content + token },
            ];
          }
          return [...prev, { role: "assistant", content: token }];
        });
        scrollToBottom();
      }
      if (msg.type === "done") {
        setIsLoading(false);
      }
    });
    return unsub;
  }, []);

  function scrollToBottom() {
    setTimeout(() => {
      messagesRef.current?.scrollTo({
        top: messagesRef.current.scrollHeight,
        behavior: "smooth",
      });
    }, 10);
  }

  function send() {
    const text = inputValue.trim();
    if (!text || isLoading) return;
    setMessages((prev) => [...prev, { role: "user", content: text }]);
    setInputValue("");
    setIsLoading(true);
    sendToHost({ type: "chat", content: text });
    scrollToBottom();
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }

  return (
    <main>
      <div className="messages" ref={messagesRef}>
        {messages.map((msg, i) => (
          <div key={i} className={`message ${msg.role}`}>
            <pre>{msg.content}</pre>
          </div>
        ))}
        {isLoading && (
          <div className="message assistant loading">
            <span className="dot" />
            <span className="dot" />
            <span className="dot" />
          </div>
        )}
      </div>

      <div className="input-bar">
        <textarea
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Ask OxideCode…"
          rows={3}
          disabled={isLoading}
        />
        <button onClick={send} disabled={isLoading || !inputValue.trim()}>
          Send
        </button>
      </div>
    </main>
  );
}
