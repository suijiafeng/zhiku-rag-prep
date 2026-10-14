import { Card, Table, Tag, Typography } from "antd";
import { Fragment } from "react";
import type { Candidate, DebugTrace, Stage, StageStatus } from "./fixtures";

const statusView: Record<StageStatus, { color: string; label: string }> = {
  success: { color: "success", label: "成功" },
  failed: { color: "error", label: "失败" },
  skipped: { color: "default", label: "跳过" },
  cancelled: { color: "warning", label: "取消" },
};

export function totalElapsedMs(stages: Stage[]) {
  return stages.reduce((sum, s) => sum + (s.elapsedMs ?? 0), 0);
}

const seconds = (ms: number) => `${(ms / 1000).toFixed(2)}s`;
const score = (v: number | null) => (v === null ? "—" : v.toFixed(2));
const usageLabel: Record<Candidate["usage"], string> = { cited: "引用", context: "入上下文", none: "—" };

export function RetrievalDebugPage({ trace, live = false }: { trace: DebugTrace; live?: boolean }) {
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">检索调试</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            {live ? "实时轨迹" : "示例轨迹"} · 问题：「{trace.question}」 · trace_id {trace.traceId}
            {live ? "" : " · 未连接本地服务"}
          </Typography.Text>
        </div>
      </div>

      <Card
        size="small"
        className="debug-card"
        title={<>RAG 链路 · 已运行阶段耗时 {seconds(totalElapsedMs(trace.stages))}</>}
        extra={<span className="trace-pill mono">{trace.configVersion}</span>}
      >
        <div className="stage-context">
          <span>原问题：{trace.question}</span>
          <span>知识库：{trace.knowledgeBase}</span>
          <span>{trace.scopeVersion}</span>
        </div>
        <ol className="stage-flow">
          {trace.stages.map((s, i) => (
            <Fragment key={s.key}>
              {i > 0 && <li className="stage-arrow" aria-hidden="true">→</li>}
              <li className={`stage stage-${s.status}${s.key === "generation" ? " stage-final" : ""}`}>
                <div className="stage-name">
                  <span className="stage-dot" aria-hidden="true" />
                  {i + 1} {s.name}
                </div>
                <div className="stage-time mono">{s.elapsedMs === null ? "—" : seconds(s.elapsedMs)}</div>
                <Tag color={statusView[s.status].color}>{statusView[s.status].label}</Tag>
                <div className="stage-summary">{s.summary}</div>
              </li>
            </Fragment>
          ))}
        </ol>
      </Card>

      <Card
        size="small"
        className="debug-card"
        title={<>候选 Chunk 各阶段得分 <span className="card-unit">仅显示授权范围内候选；绿色行为回答引用的 chunk；跳过的阶段显示 —</span></>}
      >
        <Table
          size="small"
          pagination={false}
          rowKey="chunk"
          dataSource={trace.candidates}
          scroll={{ x: 760 }}
          rowClassName={(c) => (c.usage === "cited" ? "row-cited" : "")}
          columns={[
            { title: "Chunk", dataIndex: "chunk", render: (v: string) => <span className="chunk-seq">{v}</span> },
            { title: "所属文档", dataIndex: "document", render: (v: string, c) => <>{v} <span className="card-unit">{c.version}</span></> },
            { title: "向量分", dataIndex: "vector", className: "mono", render: score },
            { title: "词法分", dataIndex: "lexical", className: "mono", render: score },
            { title: "融合分", dataIndex: "fused", className: "mono", render: score },
            { title: "重排分", dataIndex: "rerank", className: "mono", render: score },
            { title: "最终排名", dataIndex: "finalRank", className: "mono", render: (n: number) => `#${n}` },
            {
              title: "入选",
              dataIndex: "usage",
              render: (u: Candidate["usage"]) => <span className={`usage-${u}`}>{usageLabel[u]}</span>,
            },
          ]}
        />
      </Card>

      <Card size="small" className="debug-card" title="本次检索参数">
        <dl className="param-grid">
          {trace.params.map((p) => (
            <div key={p.label} className="param-row">
              <dt>{p.label}</dt>
              <dd>{p.value}</dd>
            </div>
          ))}
        </dl>
      </Card>
    </div>
  );
}
