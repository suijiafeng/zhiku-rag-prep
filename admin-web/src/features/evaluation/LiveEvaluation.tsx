import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type ApiClient, type DocumentInfo, type EvalCase, type EvalResult, type EvalRun, type EvalSummary } from "../../api/client";
import { EmptyState, ErrorState, LoadingList } from "../../components/StateViews";

const LABEL: Record<EvalCase["label"], string> = { answerable: "可回答", clarify: "应澄清", handoff: "应转人工" };
const pct = (n: number, d: number) => (d ? ` · ${((n / d) * 100).toFixed(1)}%` : "");

/** Only retrieval metrics that a run actually measured; answer accuracy needs human review and is not shown. */
export function metricCards(summary: EvalSummary | undefined, k: number) {
  if (!summary) {
    return [`Hit@${k}（证据位置覆盖）`, "证据 Recall", "P95 检索延迟", "评测准备失败"].map((title) => ({ title, value: "—", sub: "尚未运行" }));
  }
  return [
    { title: `Hit@${k}（证据位置覆盖）`, value: `${summary.hit.num}/${summary.hit.den}`, sub: pct(summary.hit.num, summary.hit.den).slice(3) || "—" },
    { title: "证据 Recall", value: `${summary.recall.num}/${summary.recall.den}`,
      sub: summary.recallMacro === null ? "—" : `宏平均 ${(summary.recallMacro * 100).toFixed(1)}%` },
    { title: "P95 检索延迟", value: summary.p95Ms === null ? "—" : `${summary.p95Ms} ms`, sub: "仅检索，不含生成" },
    { title: "评测准备失败", value: String(summary.prepFailed), sub: "证据无法映射到当前索引，不计入分母" },
  ];
}

export function CaseList({ cases, onApprove, onEdit }: { cases: EvalCase[]; onApprove: (c: EvalCase) => void; onEdit: (c: EvalCase) => void }) {
  if (cases.length === 0) return <EmptyState icon="chart" title="暂无评测用例" description="新建用例，或在问答记录中把点踩加入评测候选。" />;
  return (
    <ul className="session-list">
      {cases.map((c) => {
        const needsEvidence = c.label === "answerable" && c.evidence.length === 0;
        return (
          <li key={c.id} className="eval-case">
            <div>
              <Tag color={c.status === "approved" ? "green" : "orange"}>{c.status === "approved" ? "已批准" : "候选"}</Tag>
              <Tag>{LABEL[c.label]}</Tag>
              {needsEvidence && <Tag color="red">待补证据</Tag>}
              <span>{c.question}</span>
            </div>
            {c.evidence.map((e, i) => (
              <div key={i} className="card-unit">证据：{e.documentTitle} {e.versionLabel} · 「{e.quote}」 [{e.startOffset}, {e.endOffset})</div>
            ))}
            {c.status === "candidate" && (
              <Space size={4}>
                <Button size="small" onClick={() => onEdit(c)}>补证据</Button>
                <Button size="small" type="primary" disabled={needsEvidence} onClick={() => onApprove(c)}>批准</Button>
              </Space>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function EvidenceForm({ docs, initialQuestion, onSubmit, onCancel, submitLabel }: {
  docs: DocumentInfo[];
  initialQuestion?: string;
  submitLabel: string;
  onSubmit: (v: { question: string; label: EvalCase["label"]; evidence: { versionId: string; quote: string }[] }) => Promise<void>;
  onCancel?: () => void;
}) {
  const [question, setQuestion] = useState(initialQuestion ?? "");
  const [label, setLabel] = useState<EvalCase["label"]>("answerable");
  const [versionId, setVersionId] = useState<string>();
  const [quote, setQuote] = useState("");
  const versions = docs.flatMap((d) => d.versions.map((v) => ({ value: v.id, label: `${d.title} ${v.versionLabel}` })));
  return (
    <div className="eval-form">
      {initialQuestion === undefined && (
        <>
          <Input placeholder="问题" value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={2000} aria-label="评测问题" />
          <Select<EvalCase["label"]> value={label} onChange={setLabel} aria-label="标签"
            options={(Object.keys(LABEL) as EvalCase["label"][]).map((k) => ({ value: k, label: LABEL[k] }))} />
        </>
      )}
      <Select<string> placeholder="证据所在文档版本" options={versions} onChange={setVersionId} aria-label="证据版本" {...(versionId ? { value: versionId } : {})} />
      <Input.TextArea rows={2} placeholder="粘贴原文摘录（服务端在该版本原文中定位字符区间）" value={quote} onChange={(e) => setQuote(e.target.value)} aria-label="原文摘录" />
      <Space>
        <Button type="primary" size="small" disabled={!question.trim() || (label === "answerable" && (!versionId || quote.trim().length < 2))}
          onClick={() => void onSubmit({ question: question.trim(), label, evidence: versionId && quote.trim() ? [{ versionId, quote: quote.trim() }] : [] })}>
          {submitLabel}
        </Button>
        {onCancel && <Button size="small" onClick={onCancel}>取消</Button>}
      </Space>
    </div>
  );
}

export function LiveEvaluation({ client, kbId }: { client: ApiClient; kbId: string }) {
  const [cases, setCases] = useState<EvalCase[]>();
  const [run, setRun] = useState<EvalRun>();
  const [docs, setDocs] = useState<DocumentInfo[]>([]);
  const [k, setK] = useState(5);
  const [editing, setEditing] = useState<EvalCase>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError>();

  const fail = (e: unknown) => setError(e instanceof ApiError ? e : new ApiError(0, "NETWORK", "请求失败"));
  const load = useCallback(async () => {
    try {
      const [c, r, d] = await Promise.all([client.evalCases(kbId), client.latestRun(kbId), client.documents(kbId)]);
      setCases(c);
      setRun(r ?? undefined);
      setDocs(d);
    } catch (e) {
      fail(e);
    }
  }, [client, kbId]);
  useEffect(() => void load(), [load]);

  const act = async (fn: () => Promise<unknown>) => {
    setError(undefined);
    try {
      await fn();
      setEditing(undefined);
    } catch (e) {
      fail(e);
    }
    await load();
  };

  const execute = async () => {
    setBusy(true);
    setError(undefined);
    try {
      setRun(await client.runEvaluation(kbId, k));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">评测中心</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            {cases ? `${cases.length} 个用例 · ${cases.filter((c) => c.status === "approved").length} 个已批准` : "加载中"}
            {run ? ` · 上次运行 ${new Date(run.createdAt).toLocaleString("zh-CN")} · ${run.model ?? "未检索"}` : " · 尚未运行"} · 检索评测，不调用模型
          </Typography.Text>
        </div>
        <Space>
          <InputNumber min={1} max={30} value={k} onChange={(v) => setK(v ?? 5)} addonBefore="K" aria-label="K" />
          <Button type="primary" loading={busy} onClick={() => void execute()}>运行评测</Button>
        </Space>
      </div>
      {error && <div className="scaffold-alert"><ErrorState code={error.code} message={error.message} requestId={error.requestId} /></div>}
      <Alert type="info" showIcon className="scaffold-alert" title="小样本只支持试点结论"
        description="回答正确与完整、引用语义支持需要人工判定，本页暂不计算；不得把检索命中率当作回答质量。" />
      <Row gutter={[16, 16]}>
        {metricCards(run?.summary, run?.k ?? k).map((m) => (
          <Col key={m.title} xs={24} sm={12} xl={6}>
            <Card className="metric-card">
              <div className="metric-title">{m.title}</div>
              <div className="metric-value">{m.value}</div>
              <div className="metric-delta down">{m.sub}</div>
            </Card>
          </Col>
        ))}
        <Col xs={24} xl={15}>
          <Card size="small" title="评测明细">
            {!run && <EmptyState icon="chart" title="暂无评测结果" description="批准至少一个用例后运行评测。" />}
            {run && (
              <Table<EvalResult> size="small" rowKey="caseId" pagination={false} dataSource={run.results} scroll={{ x: 640 }}
                columns={[
                  { title: "问题", dataIndex: "question" },
                  { title: "证据命中", key: "e", className: "mono", render: (_, r) => (r.mappingFailed ? "—" : `${r.evidenceHit}/${r.evidenceTotal}`) },
                  { title: "延迟", dataIndex: "latencyMs", className: "mono", render: (v: number, r) => (r.mappingFailed ? "—" : `${v} ms`) },
                  {
                    title: "结果",
                    key: "r",
                    render: (_, r) => r.mappingFailed ? <Tag color="warning">评测准备失败</Tag> : r.hit ? <Tag color="success">命中</Tag> : <Tag color="error">未命中</Tag>,
                  },
                ]} />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Card size="small" className="side-card" title="评测用例">
            {!cases ? <LoadingList /> : (
              <CaseList cases={cases} onApprove={(c) => void act(() => client.approveEvalCase(kbId, c.id))} onEdit={setEditing} />
            )}
          </Card>
          <Card size="small" className="side-card" title={editing ? `补证据 · ${editing.question}` : "新建用例"}>
            {editing ? (
              <EvidenceForm key={editing.id} docs={docs} initialQuestion={editing.question} submitLabel="保存证据" onCancel={() => setEditing(undefined)}
                onSubmit={(v) => act(() => client.setEvalEvidence(kbId, editing.id, v.evidence))} />
            ) : (
              <EvidenceForm docs={docs} submitLabel="创建候选"
                onSubmit={(v) => act(() => client.createEvalCase(kbId, v.evidence.length ? v : { question: v.question, label: v.label }))} />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
