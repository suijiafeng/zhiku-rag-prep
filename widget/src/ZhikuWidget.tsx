import { Fragment, useEffect, useRef, useState, type CSSProperties, type FormEvent } from "react";
import { rateMessage, streamChat, type FeedbackReason } from "./chatClient.js";
import type { AccessTokenGetter, Citation } from "./contracts.js";
import { WIDGET_CSS } from "./widgetStyles.js";

export interface ZhikuWidgetProps {
  /** 同源或白名单内的后端问答 URL（…/api/chat）；绝不是模型提供方 URL。 */
  endpoint: string;
  knowledgeBaseId: string;
  /** 每次请求获取的短期 widget token（由宿主后端调用 POST /api/widget-tokens）。此处从不存储。 */
  getAccessToken?: AccessTokenGetter;
  title?: string;
  subtitle?: string;
  greeting?: string;
  quickQuestions?: string[];
  /** 品牌色，格式为 #rgb 或 #rrggbb；其他值回退为默认蓝色。 */
  themeColor?: string;
  defaultOpen?: boolean;
}

const DEFAULT_THEME = "#2f5bd3";
const REASONS: { key: FeedbackReason; label: string }[] = [
  { key: "WRONG", label: "答错了" },
  { key: "MISSING", label: "没答到" },
  { key: "BAD_CITATION", label: "引用不对" },
  { key: "SHOULD_REFUSE", label: "应该拒答" },
  { key: "OUTDATED", label: "内容过期" },
];

export const normalizeTheme = (color: string | undefined) =>
  color && /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i.test(color) ? color : DEFAULT_THEME;

type Turn = {
  id: number;
  question: string;
  state: "running" | "complete" | "failed" | "cancelled";
  text: string;
  citations: Citation[];
  error?: string | undefined;
  messageId?: string;
  rating?: "up" | "down";
  picking?: boolean;
};

/** 渲染不可信的回答文本；只有存在对应引用的 [n] 标记才会变成可展开开关。 */
function AnswerBody({ turn, open, onToggle }: { turn: Turn; open: number | undefined; onToggle: (n: number) => void }) {
  const parts = turn.text.split(/(\[\d+\])/u);
  const source = open === undefined ? undefined : turn.citations[open - 1];
  return (
    <>
      <div>
        {parts.map((part, i) => {
          const n = Number(/^\[(\d+)\]$/u.exec(part)?.[1] ?? NaN);
          return n >= 1 && n <= turn.citations.length ? (
            <button key={i} type="button" className="zhiku-cite" aria-expanded={open === n} aria-label={`来源 ${n}`} onClick={() => onToggle(n)}>[{n}]</button>
          ) : <Fragment key={i}>{part}</Fragment>;
        })}
      </div>
      {source && (
        <div className="zhiku-source">
          <strong>[{open}] {source.title}</strong>
          {source.excerpt}
        </div>
      )}
      <p className="zhiku-note">来源信息检查通过，请核对内容</p>
    </>
  );
}

/**
 * 可嵌入的问答组件（设计稿第 12 页）。流式输出时显示草稿，但只保留到达 validated + done 的回答；
 * 错误和取消会丢弃回答。会话只保存在组件内存中。
 */
export function ZhikuWidget({
  endpoint, knowledgeBaseId, getAccessToken, title = "知库问答", subtitle = "知识库助手", themeColor,
  greeting = "你好，我是知识库助手，可以问我公司制度、流程相关问题。", quickQuestions = [], defaultOpen = false,
}: ZhikuWidgetProps) {
  const [open, setOpen] = useState(defaultOpen);
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [expanded, setExpanded] = useState<{ turn: number; n: number }>();
  const conversation = useRef<string | undefined>(undefined);
  const controller = useRef<AbortController | null>(null);
  const body = useRef<HTMLDivElement>(null);
  const running = turns.some((t) => t.state === "running");
  const tokenOpts = getAccessToken ? { getAccessToken } : {};

  // 宿主切换知识库或接口地址后：中止在途请求、清空对话并作废迟到回调，不把旧会话带到新库。
  const context = useRef(0);
  useEffect(() => {
    context.current++;
    controller.current?.abort();
    controller.current = null;
    conversation.current = undefined;
    setTurns([]);
    setExpanded(undefined);
    return () => controller.current?.abort();
  }, [endpoint, knowledgeBaseId]);
  useEffect(() => {
    body.current?.scrollTo?.({ top: body.current.scrollHeight });
  }, [turns]);

  const update = (id: number, patch: Partial<Turn> | ((t: Turn) => Partial<Turn>)) =>
    setTurns((all) => all.map((t) => (t.id === id ? { ...t, ...(typeof patch === "function" ? patch(t) : patch) } : t)));

  const ask = async (text: string) => {
    const q = text.trim();
    if (!q || running) return;
    const id = Date.now();
    const key = context.current;
    const abort = new AbortController();
    controller.current = abort;
    setQuestion("");
    setTurns((all) => [...all, { id, question: q, state: "running", text: "", citations: [] }]);
    const outcome = await streamChat({
      endpoint,
      request: conversation.current ? { question: q, kbId: knowledgeBaseId, conversationId: conversation.current } : { question: q, kbId: knowledgeBaseId },
      signal: abort.signal,
      ...tokenOpts,
      onEvent: (e) => {
        if (e.event === "draft") update(id, (t) => ({ text: t.text + e.data.text }));
      },
    });
    // 等待期间宿主已切换上下文：这条结果属于旧对话，丢弃。
    if (key !== context.current) return;
    if (outcome.status === "complete") {
      if (outcome.conversationId) conversation.current = outcome.conversationId;
      update(id, { state: "complete", text: outcome.text, citations: outcome.citations, ...(outcome.messageId ? { messageId: outcome.messageId } : {}) });
    } else if (outcome.status === "cancelled") {
      update(id, { state: "cancelled", text: "", citations: [] });
    } else {
      update(id, { state: "failed", text: "", citations: [],
        error: `${outcome.error.message}（${outcome.error.code}）${outcome.error.retryable ? "，可稍后重新提问" : ""}` });
    }
  };

  const rate = async (turn: Turn, rating: "up" | "down", reason?: FeedbackReason) => {
    if (!turn.messageId) return;
    const result = await rateMessage({ endpoint, messageId: turn.messageId, rating, ...(reason ? { reason } : {}), ...tokenOpts });
    update(turn.id, result.ok ? { rating, picking: false, error: undefined } : { error: "反馈提交失败" });
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void ask(question);
  };

  const style = { "--zhiku-primary": normalizeTheme(themeColor) } as CSSProperties;

  return (
    <div className="zhiku-root" style={style}>
      <style>{WIDGET_CSS}</style>
      {!open && (
        <button type="button" className="zhiku-launcher" aria-label="打开知库问答" onClick={() => setOpen(true)}>💬</button>
      )}
      {open && (
        <section className="zhiku-panel" role="dialog" aria-label={title}>
          <header className="zhiku-header">
            <span className="zhiku-logo" aria-hidden="true">知</span>
            <div>
              <h2>{title}</h2>
              <p>{subtitle}</p>
            </div>
            <button type="button" className="zhiku-close" aria-label="关闭" onClick={() => setOpen(false)}>×</button>
          </header>
          <div className="zhiku-body" ref={body}>
            <div className="zhiku-bot">{greeting}</div>
            {turns.length === 0 && quickQuestions.length > 0 && (
              <div className="zhiku-quick">
                {quickQuestions.map((q) => <button key={q} type="button" className="zhiku-chip" onClick={() => void ask(q)}>{q}</button>)}
              </div>
            )}
            {turns.map((t) => (
              <Fragment key={t.id}>
                <div className="zhiku-user">{t.question}</div>
                <div className="zhiku-bot" role={t.state === "running" ? "status" : undefined} aria-live="polite">
                  {t.state === "running" && (
                    <>
                      <div>{t.text || "正在检索与生成…"}</div>
                      <button type="button" className="zhiku-stop" onClick={() => controller.current?.abort()}>停止生成</button>
                    </>
                  )}
                  {t.state === "complete" && (
                    <>
                      <AnswerBody turn={t} open={expanded?.turn === t.id ? expanded.n : undefined}
                        onToggle={(n) => setExpanded((cur) => (cur?.turn === t.id && cur.n === n ? undefined : { turn: t.id, n }))} />
                      {t.messageId && (
                        <div className="zhiku-feedback">
                          <span>这个回答有帮助吗？</span>
                          <button type="button" aria-pressed={t.rating === "up"} onClick={() => void rate(t, "up")}>赞</button>
                          <button type="button" aria-pressed={t.rating === "down"} onClick={() => update(t.id, { picking: !t.picking })}>踩</button>
                          {t.picking && REASONS.map((r) => (
                            <button key={r.key} type="button" onClick={() => void rate(t, "down", r.key)}>{r.label}</button>
                          ))}
                          {t.error && <span className="zhiku-error">{t.error}</span>}
                        </div>
                      )}
                    </>
                  )}
                  {t.state === "cancelled" && <div>已停止，未完成的回答已丢弃。</div>}
                  {t.state === "failed" && <div className="zhiku-error" role="alert">{t.error}</div>}
                </div>
              </Fragment>
            ))}
          </div>
          <form className="zhiku-form" onSubmit={submit}>
            <textarea value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={2000} rows={2} aria-label="问题"
              placeholder="输入问题…" disabled={running}
              onKeyDown={(e) => {
                // 输入法组字时的 Enter 只用于确认候选词
                if (e.key !== "Enter" || e.shiftKey || e.nativeEvent.isComposing || e.keyCode === 229) return;
                e.preventDefault();
                void ask(question);
              }} />
            <button type="submit" className="zhiku-send" disabled={running || !question.trim()}>发送</button>
          </form>
        </section>
      )}
    </div>
  );
}
