import { useEffect, useRef, useState, type FormEvent } from "react";
import { streamChat, type ChatOutcome } from "./chatClient.js";
import type { AccessTokenGetter, Citation } from "./contracts.js";

export interface ZhikuWidgetProps {
  /** Same-origin or explicitly allow-listed backend chat URL; never a model provider URL. */
  endpoint: string;
  knowledgeBaseId: string;
  /** Returns a short-lived widget token per request (POST /api/widget-tokens on the host's backend). Never stored here. */
  getAccessToken?: AccessTokenGetter;
  title?: string;
}

type View =
  | { kind: "idle" }
  | { kind: "running"; question: string; draft: string }
  | { kind: "done"; question: string; outcome: ChatOutcome };

const VALIDATED_NOTE = "来源信息检查通过，请核对内容";

function Answer({ text, citations }: { text: string; citations: Citation[] }) {
  return (
    <div className="zhiku-answer">
      <p>{text}</p>
      <ol className="zhiku-citations">
        {citations.map((c, i) => (
          <li key={`${c.versionId}-${i}`}>
            <strong>[{i + 1}] {c.title}</strong>
            <span>{c.excerpt}</span>
          </li>
        ))}
      </ol>
      <p className="zhiku-note">{VALIDATED_NOTE}</p>
    </div>
  );
}

/**
 * Minimal embeddable Q&A. Drafts are shown while streaming but kept only when the server reaches
 * validated + done; errors and cancellation discard them. Plain React text rendering, no HTML injection.
 */
export function ZhikuWidget({ endpoint, knowledgeBaseId, getAccessToken, title = "知库助手" }: ZhikuWidgetProps) {
  const [question, setQuestion] = useState("");
  const [view, setView] = useState<View>({ kind: "idle" });
  const controller = useRef<AbortController | null>(null);

  useEffect(() => () => controller.current?.abort(), []);

  const ask = async (event: FormEvent) => {
    event.preventDefault();
    const q = question.trim();
    if (!q || view.kind === "running") return;
    const abort = new AbortController();
    controller.current = abort;
    setQuestion("");
    setView({ kind: "running", question: q, draft: "" });
    const outcome = await streamChat({
      endpoint,
      request: { question: q, kbId: knowledgeBaseId },
      signal: abort.signal,
      ...(getAccessToken ? { getAccessToken } : {}),
      onEvent: (e) => {
        if (e.event === "draft") setView((v) => (v.kind === "running" ? { ...v, draft: v.draft + e.data.text } : v));
      },
    });
    setView({ kind: "done", question: q, outcome });
  };

  return (
    <section className="zhiku-widget" aria-label={title}>
      <h2>{title}</h2>
      {view.kind === "idle" && <p role="status">输入问题，回答会附带可核对的来源。</p>}
      {view.kind !== "idle" && <p className="zhiku-question">{view.question}</p>}
      {view.kind === "running" && (
        <div role="status" aria-live="polite">
          <p className="zhiku-draft">{view.draft || "正在检索与生成…"}</p>
          <button type="button" onClick={() => controller.current?.abort()}>停止生成</button>
        </div>
      )}
      {view.kind === "done" && view.outcome.status === "complete" && <Answer text={view.outcome.text} citations={view.outcome.citations} />}
      {view.kind === "done" && view.outcome.status === "cancelled" && <p role="status">已停止，未完成的回答已丢弃。</p>}
      {view.kind === "done" && view.outcome.status === "failed" && (
        <p role="alert">{view.outcome.error.message}（{view.outcome.error.code}）</p>
      )}
      <form onSubmit={ask}>
        <textarea value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={2000} rows={2}
          aria-label="问题" placeholder="输入问题" disabled={view.kind === "running"} />
        <button type="submit" disabled={view.kind === "running" || !question.trim()}>发送</button>
      </form>
    </section>
  );
}
