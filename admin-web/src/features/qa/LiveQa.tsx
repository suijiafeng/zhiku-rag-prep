import { Alert, Button, Card, Input, Tag, Typography } from "antd";
import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { type ApiClient, type Conversation, type HistoryMessage } from "../../api/client";
import { streamAnswer } from "../../api/chatStream";
import { AnswerText } from "./AnswerText";
import { FeedbackBar } from "./FeedbackBar";
import { OutcomeNote } from "./outcome";
import { EmptyState, ErrorState } from "../../components/StateViews";
import { chatReducer, createRequestGate, initialChatState, visibleCitations, type Citation } from "./chatState";

const VALIDATED_NOTE = "来源信息检查通过，请核对内容";

function Sources({ citations, active }: { citations: Citation[]; active: number | undefined }) {
  if (citations.length === 0) return <Typography.Text type="secondary">选择一条回答查看来源。</Typography.Text>;
  return (
    <>
      {citations.map((c, i) => (
        <div key={`${c.versionId}-${i}`} className={active === i + 1 ? "source-card active" : "source-card"}>
          <div className="source-head">
            <span className="chunk-seq">#{i + 1}</span>
            <span className="source-title">{c.title}</span>
          </div>
          <div className="chunk-text">{c.excerpt}</div>
        </div>
      ))}
    </>
  );
}

/** Real Q&A against POST /api/chat. Partial drafts are discarded unless the stream reaches validated + done. */
export function LiveQa({ client, kbId, modelEnabled }: { client: ApiClient; kbId: string; modelEnabled: boolean }) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationId, setConversationId] = useState<string>();
  const [history, setHistory] = useState<HistoryMessage[]>([]);
  const [selected, setSelected] = useState<string>();
  const [question, setQuestion] = useState("");
  const [active, setActive] = useState<number>();
  const [error, setError] = useState<string>();
  const [stream, dispatch] = useReducer(chatReducer, initialChatState);
  const abort = useRef<AbortController>(undefined);
  const gate = useRef(createRequestGate());

  /** Leaves the current request behind: aborts it, ignores its late events and clears the draft. */
  const leave = useCallback(() => {
    abort.current?.abort();
    gate.current.invalidate();
    dispatch({ type: "reset" });
  }, []);

  const loadConversations = useCallback(async () => {
    try {
      setConversations((await client.conversations()).filter((c) => c.kbId === kbId));
    } catch {
      setError("无法读取会话历史");
    }
  }, [client, kbId]);

  const loadHistory = useCallback(async (id: string | undefined) => {
    if (!id) return setHistory([]);
    try {
      const messages = await client.messages(id);
      setHistory(messages);
      setSelected(messages.at(-1)?.id);
    } catch {
      setError("无法读取该会话");
    }
  }, [client]);

  useEffect(() => {
    leave();
    setConversationId(undefined);
    setHistory([]);
    void loadConversations();
  }, [loadConversations, leave]);

  useEffect(() => () => abort.current?.abort(), []);

  const running = stream.status === "streaming" || stream.status === "validated";
  const send = async (text: string) => {
    const q = text.trim();
    if (!q || running) return;
    setQuestion("");
    setError(undefined);
    abort.current = new AbortController();
    const generation = gate.current.next();
    const current = () => gate.current.isCurrent(generation);
    dispatch({ type: "start", question: q });
    let target = conversationId;
    await streamAnswer(client, conversationId ? { kbId, question: q, conversationId } : { kbId, question: q },
      (action) => { if (current()) dispatch(action); }, {
        signal: abort.current.signal,
        onConversation: (id) => { if (current()) { target = id; setConversationId(id); } },
      });
    if (!current()) return;
    await loadConversations();
    await loadHistory(target);
  };

  const stop = () => {
    abort.current?.abort();
    dispatch({ type: "cancel" });
  };

  const selectedMessage = history.find((m) => m.id === selected);
  const citations = visibleCitations(running, stream.citations, selectedMessage);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">问答工作台</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">已连接本地服务 · 回答只依据当前授权且生效的版本</Typography.Text>
        </div>
      </div>
      {!modelEnabled && <Alert type="warning" showIcon className="scaffold-alert" title="服务端未配置对话模型" description="设置 ZHIKU_CHAT_PROVIDER=ollama 后重启服务。" />}
      {error && <Alert type="error" showIcon closable className="scaffold-alert" title={error} onClose={() => setError(undefined)} />}
      <div className="qa-grid">
        <Card size="small" title="会话历史">
          <Button type="primary" block onClick={() => { leave(); setConversationId(undefined); setHistory([]); }}>+ 新会话</Button>
          <ul className="session-list">
            {conversations.map((c) => (
              <li key={c.id}>
                <button type="button" className={c.id === conversationId ? "session active" : "session"}
                  onClick={() => { leave(); setConversationId(c.id); void loadHistory(c.id); }}>
                  <span>{c.title}</span>
                  <span className="mono session-time">{new Date(c.updatedAt).toLocaleString("zh-CN")}</span>
                </button>
              </li>
            ))}
          </ul>
        </Card>

        <Card size="small" className="qa-main">
          {history.length === 0 && stream.status === "idle" && (
            <EmptyState icon="chat" title="暂无问答记录" description="输入问题开始提问；回答只依据当前授权且生效的版本。" />
          )}
          {history.map((m) => (
            <div key={m.id} onClick={() => setSelected(m.id)}>
              <div className="bubble-user">{m.question}</div>
              <div className={m.id === selected ? "answer-card selected" : "answer-card"}>
                {m.status !== "complete" ? (
                  <OutcomeNote status={m.status} errorCode={m.errorCode} />
                ) : m.hidden ? (
                  <Alert type="warning" showIcon title="来源已撤回或变更，这条回答已隐藏" />
                ) : (
                  <>
                    <AnswerText text={m.answer ?? ""} citationCount={m.citations.length} activeIndex={m.id === selected ? active : undefined} onCite={setActive} />
                    <div className="answer-foot">
                      <Tag color="blue">{VALIDATED_NOTE}</Tag>
                      {m.stale && <Tag color="orange">所依据版本已不是当前生效版本</Tag>}
                    </div>
                    <FeedbackBar current={m.feedback} onSubmit={async (body) => {
                      try {
                        await client.rate(m.id, body);
                      } catch {
                        setError("反馈提交失败");
                      }
                      await loadHistory(conversationId);
                    }} />
                  </>
                )}
              </div>
            </div>
          ))}
          {stream.status !== "idle" && stream.status !== "complete" && (
            <>
              <div className="bubble-user">{stream.question}</div>
              <div className="replay-card">
                {running && (
                  <>
                    <p className="answer-text">{stream.text || "正在检索与生成…"}<span className="caret" aria-hidden="true" /></p>
                    <Button className="stop-btn" onClick={stop}>■ 停止生成</Button>
                  </>
                )}
                {stream.status === "cancelled" && <Alert type="info" showIcon title="已停止生成，未完成的回答已丢弃" />}
                {stream.status === "failed" && stream.error?.code === "ANSWER_SAVED" && <Alert type="info" showIcon title={stream.error.message} />}
                {stream.status === "failed" && stream.error?.code !== "ANSWER_SAVED" && (
                  <ErrorState code={stream.error?.code ?? "UNKNOWN"} message={stream.error?.message ?? "生成失败"}
                    requestId={stream.error?.requestId || undefined} onRetry={() => void send(stream.question)} />
                )}
              </div>
            </>
          )}
          <div className="composer">
            <Input.TextArea rows={3} value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={2000}
              placeholder={modelEnabled ? "输入问题…（Enter 发送，Shift+Enter 换行）" : "对话模型未配置"} disabled={!modelEnabled || running}
              aria-label="问题输入"
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); void send(question); } }} />
            <div className="composer-foot">
              <span>精确向量检索 · 引用元数据检查</span>
              <Button type="primary" disabled={!modelEnabled || running || !question.trim()} onClick={() => void send(question)}>发送</Button>
            </div>
          </div>
        </Card>

        <Card size="small" title="引用来源">
          <Sources citations={citations} active={active} />
        </Card>
      </div>
    </div>
  );
}
