import { Descriptions, Table, Tag } from "antd";
import type { StoredTrace, TraceStage } from "../../api/client";

const ORIGIN: Record<StoredTrace["origin"], string> = { chat: "问答", debug: "检索调试", evaluation: "评测" };
const STATUS: Record<TraceStage["status"], { label: string; color: string }> = {
  success: { label: "成功", color: "success" },
  failed: { label: "失败", color: "error" },
  skipped: { label: "跳过", color: "default" },
  cancelled: { label: "已取消", color: "warning" },
};

/** 存档轨迹只含元数据：阶段状态与耗时、候选编号与得分，不含问题和片段正文。 */
export function StoredTraceView({ trace }: { trace: StoredTrace }) {
  return (
    <>
      <Descriptions size="small" column={2} items={[
        { key: "origin", label: "来源", children: ORIGIN[trace.origin] },
        { key: "at", label: "查询时点", children: new Date(trace.at).toLocaleString("zh-CN") },
        { key: "model", label: "Embedding", children: trace.embeddingModel ? `${trace.embeddingModel} · ${trace.dimension ?? "—"} 维` : "未配置" },
        { key: "k", label: "召回 K", children: trace.k },
      ]} />
      <Table<TraceStage> size="small" rowKey="key" pagination={false} dataSource={trace.stages} className="trace-stages"
        columns={[
          { title: "阶段", dataIndex: "name" },
          { title: "状态", dataIndex: "status", render: (s: TraceStage["status"]) => <Tag color={STATUS[s].color}>{STATUS[s].label}</Tag> },
          { title: "耗时", dataIndex: "elapsedMs", className: "mono", render: (v: number | null) => (v === null ? "—" : `${v} ms`) },
          { title: "摘要", dataIndex: "summary" },
        ]} />
      <Table size="small" rowKey="rank" pagination={false} dataSource={trace.candidates} locale={{ emptyText: "无候选" }}
        columns={[
          { title: "排名", dataIndex: "rank", className: "mono" },
          { title: "文档", dataIndex: "documentTitle", render: (t: string | null) => t ?? "文档已删除" },
          { title: "相似度", dataIndex: "score", className: "mono", render: (v: number) => v.toFixed(4) },
          { title: "切块", dataIndex: "chunkId", className: "mono", render: (v: string) => v.slice(0, 8) },
        ]} />
    </>
  );
}
