import { Button, Card, Col, Row, Table, Tag, Typography } from "antd";
import { useState } from "react";
import type { EvalRow, EvaluationData } from "./fixtures";
import { percent, resultOf, summarize, type EvalResult } from "./metrics";

const resultView: Record<EvalResult, { color: string; label: string }> = {
  pass: { color: "success", label: "通过" },
  fail: { color: "error", label: "未通过" },
  pending: { color: "default", label: "待人工判定" },
  "prep-failed": { color: "warning", label: "评测准备失败" },
};

const yesNo = (v: boolean | null) => (v === null ? "—" : v ? "是" : "否");

export function EvaluationPage({ data }: { data: EvaluationData }) {
  const s = summarize(data.rows);
  const failures = data.rows.filter((r) => resultOf(r) === "fail");
  const [selectedId, setSelectedId] = useState(failures[0]?.id);
  const selected = failures.find((r) => r.id === selectedId);

  const metrics = [
    { title: "Hit@5（证据位置覆盖）", value: `${s.hit.num}/${s.hit.den}`, sub: percent(s.hit) },
    { title: "回答正确且完整（人工）", value: `${s.correct.num}/${s.correct.den}`, sub: percent(s.correct) },
    { title: "引用支持（人工）", value: `${s.citation.num}/${s.citation.den}`, sub: percent(s.citation) },
    { title: "端到端 P95", value: s.p95Ms === null ? "—" : `${(s.p95Ms / 1000).toFixed(1)}s`, sub: "固定环境与并发待定" },
  ];

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">评测中心</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            {data.dataset} · {data.rows.length} 个问题 · 示例运行 {data.lastRun} · 种子试点规模，仅支持试点结论
          </Typography.Text>
        </div>
        <Button type="primary" disabled title="评测运行尚未实现；额外模型调用需先提示用量">运行评测</Button>
      </div>

      <Row gutter={[16, 16]}>
        {metrics.map((m) => (
          <Col key={m.title} xs={24} sm={12} xl={6}>
            <Card className="metric-card">
              <div className="metric-title">{m.title}</div>
              <div className="metric-value">{m.value}</div>
              <div className="metric-delta down">{m.sub}</div>
            </Card>
          </Col>
        ))}

        <Col xs={24} xl={16}>
          <Card
            size="small"
            title={<>评测明细 <span className="card-unit mono">{s.passed} / {s.evaluated} 通过 · {s.prepFailed} 题准备失败不计入</span></>}
          >
            <Table<EvalRow>
              size="small"
              pagination={false}
              rowKey="id"
              dataSource={data.rows}
              scroll={{ x: 720 }}
              columns={[
                { title: "问题", dataIndex: "question" },
                { title: "期望证据", dataIndex: "expectedEvidence", render: (v: string) => <span className="card-unit">{v}</span> },
                { title: "证据命中", key: "hit", className: "mono", render: (_, r) => (r.mappingFailed ? "—" : `${r.evidenceHit}/${r.evidenceTotal}`) },
                { title: "引用支持", dataIndex: "citationSupported", render: yesNo },
                {
                  title: "结果",
                  key: "result",
                  render: (_, r) => {
                    const v = resultView[resultOf(r)];
                    return <Tag color={v.color}>{v.label}</Tag>;
                  },
                },
              ]}
            />
          </Card>
        </Col>

        <Col xs={24} xl={8}>
          <Card size="small" className="side-card" title={<>失败案例 <Tag color="error">{failures.length} 个</Tag></>}>
            <ul className="session-list">
              {failures.map((r) => (
                <li key={r.id}>
                  <button type="button" className={r.id === selectedId ? "session active" : "session"} onClick={() => setSelectedId(r.id)}>
                    <span>{r.question}</span>
                    <span className="session-time">{r.rootCause} · {r.evidenceHit}/{r.evidenceTotal} 命中</span>
                  </button>
                </li>
              ))}
            </ul>
            {selected && (
              <div className="cause-detail">
                <div>根因：{selected.rootCause}</div>
                <div className="card-unit">{selected.note}</div>
              </div>
            )}
          </Card>

          <Card size="small" className="side-card" title={<>评测候选 <Tag color="orange">{data.candidates.length} 待补齐</Tag></>}>
            <Typography.Text type="secondary" className="page-sub">
              来自用户点踩；需人工补齐期望答案、必要事实、原文位置并检查敏感信息，再审批进入新数据集版本
            </Typography.Text>
            {data.candidates.map((c) => (
              <div key={c.question} className="candidate-row">
                <div>
                  <div>{c.question}</div>
                  <div className="card-unit">{c.source}</div>
                </div>
                <Button size="small" disabled title="候选审核尚未实现">补齐真值后审批</Button>
              </div>
            ))}
          </Card>

          <Card size="small" className="side-card" title="改进预览">
            <div>{data.proposal.title}</div>
            <ul className="proposal-list">
              {data.proposal.items.map((i) => <li key={i}>{i}</li>)}
            </ul>
            <div className="card-unit">{data.proposal.impact}</div>
            <div className="proposal-actions">
              <Button size="small" disabled title="未实现">保存候选</Button>
              <Button size="small" disabled title="未实现">评测</Button>
              <Button size="small" disabled title="未实现">发布</Button>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
