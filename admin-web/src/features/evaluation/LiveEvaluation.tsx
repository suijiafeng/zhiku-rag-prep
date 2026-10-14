import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type ApiClient, type DocumentInfo, type EvalCase, type EvalResult, type EvalRun, type EvalSummary, type EvidenceInput, type CaseDetails } from "../../api/client";
import { EmptyState, ErrorState, LoadingList } from "../../components/StateViews";

const LABEL: Record<EvalCase["label"], string> = { answerable: "可回答", clarify: "应澄清", handoff: "应转人工" };
const pct = (n: number, d: number) => (d ? ` · ${((n / d) * 100).toFixed(1)}%` : "");

/** 只显示某次运行实际测得的检索指标；回答准确率需要人工评审，不在此显示。 */
export function metricCards(summary: EvalSummary | undefined, k: number) {
  if (!summary) {
    return [`Hit@${k}（证据位置覆盖）`, "证据 Recall", "P95 检索延迟", "未计入分母"].map((title) => ({ title, value: "—", sub: "尚未运行" }));
  }
  return [
    { title: `Hit@${k}（证据位置覆盖）`, value: `${summary.hit.num}/${summary.hit.den}`, sub: pct(summary.hit.num, summary.hit.den).slice(3) || "—" },
    { title: "证据 Recall", value: `${summary.recall.num}/${summary.recall.den}`,
      sub: summary.recallMacro === null ? "—" : `宏平均 ${(summary.recallMacro * 100).toFixed(1)}%` },
    { title: "P95 检索延迟", value: summary.p95Ms === null ? "—" : `${summary.p95Ms} ms`, sub: "仅检索，不含生成" },
    { title: "未计入分母", value: String(summary.prepFailed + (summary.sourceUnavailable ?? 0)),
      sub: `准备失败 ${summary.prepFailed}（无法映射到当前索引）· 来源失效 ${summary.sourceUnavailable ?? 0}（已撤回、删除或不在有效期）` },
  ];
}

export function resultTag(r: EvalResult) {
  if (r.sourceUnavailable) return "来源已失效";
  if (r.mappingFailed) return "评测准备失败";
  return r.hit ? "命中" : "未命中";
}

const RESULT_COLOR: Record<string, string> = { 来源已失效: "default", 评测准备失败: "warning", 命中: "success", 未命中: "error" };

export function evidenceEntry(versionId: string, quote: string, occurrence: number | undefined): EvidenceInput {
  return occurrence ? { versionId, quote: quote.trim(), occurrence } : { versionId, quote: quote.trim() };
}

/** 可回答用例批准前还缺的项（服务端同样校验）。 */
export function missingForApproval(c: EvalCase): string[] {
  if (c.label !== "answerable") return [];
  return [
    ...(c.evidence.length === 0 ? ["证据"] : []),
    ...(c.expectedAnswer ? [] : ["参考答案"]),
    ...(c.requiredFacts.length === 0 ? ["必要事实"] : []),
  ];
}

export function parseFacts(text: string) {
  return text.split("\n").map((f) => f.trim()).filter(Boolean);
}

export interface DetailsInput {
  expectedAnswer: string;
  facts: string;
  appliesTo: string;
  /** datetime-local 输入值，按浏览器本地时间解释。 */
  queryAt: string;
}

export function detailsBody(v: DetailsInput): CaseDetails {
  return {
    ...(v.expectedAnswer.trim() ? { expectedAnswer: v.expectedAnswer.trim() } : {}),
    requiredFacts: parseFacts(v.facts),
    ...(v.appliesTo.trim() ? { appliesTo: v.appliesTo.trim() } : {}),
    ...(v.queryAt ? { queryAt: new Date(v.queryAt).toISOString() } : {}),
  };
}

export function CaseList({ cases, onApprove, onEdit }: { cases: EvalCase[]; onApprove: (c: EvalCase) => void; onEdit: (c: EvalCase) => void }) {
  if (cases.length === 0) return <EmptyState icon="chart" title="暂无评测用例" description="新建用例，或在问答记录中把点踩加入评测候选。" />;
  return (
    <ul className="session-list">
      {cases.map((c) => {
        const missing = missingForApproval(c);
        return (
          <li key={c.id} className="eval-case">
            <div>
              <Tag color={c.status === "approved" ? "green" : "orange"}>{c.status === "approved" ? "已批准" : "候选"}</Tag>
              <Tag>{LABEL[c.label]}</Tag>
              {missing.length > 0 && <Tag color="red">待补齐：{missing.join("、")}</Tag>}
              <span>{c.question}</span>
            </div>
            {c.expectedAnswer && <div className="card-unit">参考答案：{c.expectedAnswer}</div>}
            {c.requiredFacts.length > 0 && <div className="card-unit">必要事实：{c.requiredFacts.join("；")}</div>}
            {(c.appliesTo || c.queryAt) && (
              <div className="card-unit">
                {c.appliesTo && `适用：${c.appliesTo}`}{c.appliesTo && c.queryAt && " · "}
                {c.queryAt && `查询时点：${new Date(c.queryAt).toLocaleString("zh-CN")}`}
              </div>
            )}
            {c.evidence.map((e, i) => (
              <div key={i} className="card-unit">
                证据：{e.documentTitle} {e.versionLabel} · {e.available ? `「${e.quote ?? ""}」` : "来源已撤回或删除，摘录不再显示"} [{e.startOffset}, {e.endOffset})
              </div>
            ))}
            {c.status === "candidate" && (
              <Space size={4}>
                <Button size="small" onClick={() => onEdit(c)}>补齐</Button>
                <Button size="small" type="primary" disabled={missing.length > 0} onClick={() => onApprove(c)}>批准</Button>
              </Space>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function EvidenceForm({ docs, initial, onSubmit, onCancel, submitLabel }: {
  docs: DocumentInfo[];
  initial?: EvalCase;
  submitLabel: string;
  onSubmit: (v: { question: string; label: EvalCase["label"]; evidence: EvidenceInput[]; details: CaseDetails }) => Promise<void>;
  onCancel?: () => void;
}) {
  const initialQuestion = initial?.question;
  const [details, setDetails] = useState<DetailsInput>({
    expectedAnswer: initial?.expectedAnswer ?? "",
    facts: (initial?.requiredFacts ?? []).join("\n"),
    appliesTo: initial?.appliesTo ?? "",
    queryAt: "",
  });
  const [question, setQuestion] = useState(initialQuestion ?? "");
  const [label, setLabel] = useState<EvalCase["label"]>("answerable");
  const [versionId, setVersionId] = useState<string>();
  const [quote, setQuote] = useState("");
  const [occurrence, setOccurrence] = useState<number>();
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
      <InputNumber min={1} precision={0} placeholder="第几处（摘录在原文重复出现时填写）" value={occurrence ?? null}
        onChange={(v) => setOccurrence(v ?? undefined)} aria-label="第几处" className="eval-occurrence" />
      <Input.TextArea rows={2} maxLength={2000} placeholder="参考答案" value={details.expectedAnswer} aria-label="参考答案"
        onChange={(e) => setDetails({ ...details, expectedAnswer: e.target.value })} />
      <Input.TextArea rows={2} placeholder="必要事实，每行一条（最多 10 条）" value={details.facts} aria-label="必要事实"
        onChange={(e) => setDetails({ ...details, facts: e.target.value })} />
      <Input placeholder="适用对象（可空），如：正式员工" maxLength={200} value={details.appliesTo} aria-label="适用对象"
        onChange={(e) => setDetails({ ...details, appliesTo: e.target.value })} />
      <Input type="datetime-local" value={details.queryAt} aria-label="查询时点（可空，默认运行时刻）"
        onChange={(e) => setDetails({ ...details, queryAt: e.target.value })} />
      <Space>
        <Button type="primary" size="small" disabled={!question.trim() || (!initial && label === "answerable" && (!versionId || quote.trim().length < 2))}
          onClick={() => void onSubmit({ question: question.trim(), label, evidence: versionId && quote.trim() ? [evidenceEntry(versionId, quote, occurrence)] : [],
            details: detailsBody(details) })}>
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
                  { title: "证据命中", key: "e", className: "mono", render: (_, r) => (r.mappingFailed || r.sourceUnavailable ? "—" : `${r.evidenceHit}/${r.evidenceTotal}`) },
                  { title: "延迟", dataIndex: "latencyMs", className: "mono", render: (v: number, r) => (r.mappingFailed || r.sourceUnavailable ? "—" : `${v} ms`) },
                  { title: "结果", key: "r", render: (_, r) => <Tag color={RESULT_COLOR[resultTag(r)] ?? "default"}>{resultTag(r)}</Tag> },
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
          <Card size="small" className="side-card" title={editing ? `补齐 · ${editing.question}` : "新建用例"}>
            {editing ? (
              <EvidenceForm key={editing.id} docs={docs} initial={editing} submitLabel="保存" onCancel={() => setEditing(undefined)}
                onSubmit={(v) => act(async () => {
                  // 未填写新证据时保留原证据，只更新样本字段。
                  if (v.evidence.length) await client.setEvalEvidence(kbId, editing.id, v.evidence);
                  await client.setEvalCaseDetails(kbId, editing.id, v.details);
                })} />
            ) : (
              <EvidenceForm docs={docs} submitLabel="创建候选"
                onSubmit={(v) => act(() => client.createEvalCase(kbId, { question: v.question, label: v.label, ...v.details,
                  ...(v.evidence.length ? { evidence: v.evidence } : {}) }))} />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
