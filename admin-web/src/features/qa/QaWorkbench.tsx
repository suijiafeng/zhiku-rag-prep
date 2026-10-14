import { Alert, Button, Card, Input, Progress, Tag, Typography } from "antd";
import { useEffect, useReducer, useRef, useState } from "react";
import { AnswerText } from "./AnswerText";
import { chatReducer, initialChatState, type Citation } from "./chatState";
import type { QaData } from "./fixtures";

const VALIDATED_NOTE = "来源信息检查通过，请核对内容";
const REPLAY_STEP_MS = 180;

function CitationChips({ citations }: { citations: Citation[] }) {
  return (
    <div className="cite-chips">
      {citations.map((c, i) => (
        <span key={i} className="cite-chip">[{i + 1}] {c.title}</span>
      ))}
    </div>
  );
}

export function QaWorkbench({ data }: { data: QaData }) {
  const [sessionId, setSessionId] = useState(data.sessions[0]?.id);
  const [historyOpen, setHistoryOpen] = useState(true);
  const [sourcesOpen, setSourcesOpen] = useState(true);
  const [active, setActive] = useState<number | undefined>();
  const [stream, dispatch] = useReducer(chatReducer, initialChatState);
  const timers = useRef<number[]>([]);

  const clearTimers = () => {
    timers.current.forEach((t) => window.clearTimeout(t));
    timers.current = [];
  };
  useEffect(() => clearTimers, []);

  const replay = () => {
    clearTimers();
    dispatch({ type: "start", question: data.sample.question });
    data.replay.forEach((event, i) => {
      timers.current.push(window.setTimeout(() => dispatch({ type: "event", event }), (i + 1) * REPLAY_STEP_MS));
    });
  };
  const stop = () => {
    clearTimers();
    dispatch({ type: "cancel" });
  };

  const isSample = sessionId === data.sessions[0]?.id;
  const running = stream.status === "streaming" || stream.status === "validated";
  const cols = ["qa-grid", historyOpen ? "" : "no-history", sourcesOpen ? "" : "no-sources"].join(" ");

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">问答工作台</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">基于企业知识库的问答 · 所有回答均可核对引用来源</Typography.Text>
        </div>
      </div>

      <div className={cols}>
        {historyOpen && (
          <Card size="small" title="会话历史" extra={<Button type="text" size="small" aria-label="收起会话历史" onClick={() => setHistoryOpen(false)}>‹</Button>}>
            <Button type="primary" block disabled title="问答服务未接入">+ 新会话</Button>
            <ul className="session-list">
              {data.sessions.map((s) => (
                <li key={s.id}>
                  <button type="button" className={s.id === sessionId ? "session active" : "session"} onClick={() => setSessionId(s.id)}>
                    <span>{s.title}</span>
                    <span className="mono session-time">{s.updatedAt}</span>
                  </button>
                </li>
              ))}
            </ul>
          </Card>
        )}

        <Card size="small" className="qa-main">
          <div className="qa-toggles">
            {!historyOpen && <Button size="small" onClick={() => setHistoryOpen(true)}>› 会话历史</Button>}
            {!sourcesOpen && <Button size="small" onClick={() => setSourcesOpen(true)}>检索来源 ‹</Button>}
          </div>
          {isSample ? (
            <>
              <div className="bubble-user">{data.sample.question}</div>
              <div className="state-label">完成态 · 示例</div>
              <div className="answer-card">
                <div className="answer-head">
                  <span className="answer-avatar" aria-hidden="true">AI</span>
                  <div>
                    <div className="answer-name">知库助手</div>
                    <div className="answer-meta">模型未接入 · 检索未接入</div>
                  </div>
                </div>
                <AnswerText text={data.sample.answer} citationCount={data.sample.citations.length} activeIndex={active} onCite={setActive} />
                <CitationChips citations={data.sample.citations} />
                <div className="answer-foot">
                  <Tag color="blue">{VALIDATED_NOTE}</Tag>
                  <span>耗时：未接入</span>
                </div>
              </div>
            </>
          ) : (
            <Typography.Paragraph type="secondary" className="qa-empty">示例会话无内容。</Typography.Paragraph>
          )}

          <div className="state-label">
            离线回放 · 按草案 SSE 契约逐条回放示例事件，不调用任何服务
          </div>
          <div className="replay-card">
            {stream.status === "idle" && <Button onClick={replay}>▶ 回放流式示例</Button>}
            {running && (
              <>
                <p className="answer-text">{stream.text || "正在生成回答…"}<span className="caret" aria-hidden="true" /></p>
                <Button className="stop-btn" onClick={stop}>■ 停止生成</Button>
              </>
            )}
            {stream.status === "complete" && (
              <>
                <AnswerText text={stream.text} citationCount={stream.citations.length} onCite={() => {}} />
                <CitationChips citations={stream.citations} />
                <Tag color="blue">{VALIDATED_NOTE}</Tag>
                <Button size="small" onClick={replay}>再次回放</Button>
              </>
            )}
            {stream.status === "cancelled" && (
              <Alert type="info" showIcon title="已停止生成，未完成的回答已丢弃" action={<Button size="small" onClick={replay}>再次回放</Button>} />
            )}
            {stream.status === "failed" && (
              <Alert type="error" showIcon title={`生成失败（${stream.error?.code ?? "UNKNOWN"}），回答已丢弃`} action={<Button size="small" onClick={replay}>再次回放</Button>} />
            )}
          </div>

          <div className="state-label">快捷问题</div>
          <div className="quick-list">
            {data.quickQuestions.map((q) => (
              <Button key={q} shape="round" size="small" disabled title="问答服务未接入">{q}</Button>
            ))}
          </div>

          <div className="composer">
            <Input.TextArea rows={3} placeholder="输入问题…（问答服务未接入）" disabled aria-label="问题输入（未实现）" />
            <div className="composer-foot">
              <span>检索策略待确认（T4）</span>
              <Button type="primary" disabled title="问答服务未接入">发送</Button>
            </div>
          </div>
        </Card>

        {sourcesOpen && (
          <Card size="small" title="检索来源 · 示例" extra={<Button type="text" size="small" aria-label="收起检索来源" onClick={() => setSourcesOpen(false)}>›</Button>}>
            {data.sources.map((s, i) => (
              <div key={s.chunk} className={active === i + 1 ? "source-card active" : "source-card"}>
                <div className="source-head">
                  <span className="chunk-seq">#{i + 1}</span>
                  <span className="source-title">{s.title} · chunk {s.chunk}</span>
                </div>
                <div className="source-sim">
                  <span>相似度</span>
                  <Progress percent={Math.round(s.similarity * 100)} showInfo={false} size="small" />
                  <span className="mono">{s.similarity.toFixed(2)}</span>
                </div>
                <div className="chunk-text">{s.excerpt}</div>
              </div>
            ))}
          </Card>
        )}
      </div>
    </div>
  );
}
