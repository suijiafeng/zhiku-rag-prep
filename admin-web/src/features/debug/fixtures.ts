/**
 * 中性、虚构的示例 trace。遵循 PRD v0.3 首版基线：
 * 在授权范围内做精确向量检索；未启用词法检索、融合与重排。
 */
export type StageStatus = "success" | "failed" | "skipped" | "cancelled";
export type StageKey = "rewrite" | "vector" | "lexical" | "fusion" | "rerank" | "generation";
export type ScoreKey = "vector" | "lexical" | "fused" | "rerank";

export interface Stage {
  key: StageKey;
  name: string;
  status: StageStatus;
  elapsedMs: number | null;
  summary: string;
}

export interface Candidate {
  chunk: string;
  document: string;
  version: string;
  vector: number | null;
  lexical: number | null;
  fused: number | null;
  rerank: number | null;
  finalRank: number;
  usage: "cited" | "context" | "none";
}

export interface DebugTrace {
  traceId: string;
  question: string;
  knowledgeBase: string;
  scopeVersion: string;
  configVersion: string;
  stages: Stage[];
  candidates: Candidate[];
  params: { label: string; value: string }[];
}

const handbook = { document: "员工手册 v3.2.pdf", version: "v3.2" };

export const debugFixture: DebugTrace = {
  traceId: "sample-trace-0001",
  question: "年假最多可以休几天?",
  knowledgeBase: "员工公共制度库（示例）",
  scopeVersion: "授权版本 sample-7",
  configVersion: "配置 baseline-sample",
  stages: [
    { key: "rewrite", name: "查询改写", status: "skipped", elapsedMs: null, summary: "未改写，保留原问题" },
    { key: "vector", name: "向量召回", status: "success", elapsedMs: 210, summary: "精确检索 · 6 条授权候选" },
    { key: "lexical", name: "词法召回", status: "skipped", elapsedMs: null, summary: "未启用（中文分词待评测）" },
    { key: "fusion", name: "融合排序", status: "skipped", elapsedMs: null, summary: "未启用（RRF 待评测）" },
    { key: "rerank", name: "重排", status: "skipped", elapsedMs: null, summary: "未接入（资源待实测）" },
    { key: "generation", name: "上下文与生成", status: "success", elapsedMs: 1360, summary: "3 块入上下文 · 2 处引用" },
  ],
  candidates: [
    { chunk: "#002", ...handbook, vector: 0.87, lexical: null, fused: null, rerank: null, finalRank: 1, usage: "cited" },
    { chunk: "#003", ...handbook, vector: 0.81, lexical: null, fused: null, rerank: null, finalRank: 2, usage: "cited" },
    { chunk: "#004", document: "考勤制度.txt", version: "v1", vector: 0.76, lexical: null, fused: null, rerank: null, finalRank: 3, usage: "context" },
    { chunk: "#017", document: "报销流程说明.md", version: "v2", vector: 0.69, lexical: null, fused: null, rerank: null, finalRank: 4, usage: "none" },
    { chunk: "#031", document: "IT 安全规范.docx", version: "v1", vector: 0.62, lexical: null, fused: null, rerank: null, finalRank: 5, usage: "none" },
    { chunk: "#045", document: "差旅标准 v2.md", version: "v2", vector: 0.58, lexical: null, fused: null, rerank: null, finalRank: 6, usage: "none" },
  ],
  params: [
    { label: "检索方式", value: "授权范围内精确向量检索（基线）" },
    { label: "Embedding 模型 / 维度", value: "待确认（ADR-002）" },
    { label: "融合", value: "未启用；若启用建议 RRF，不直接相加不同尺度分数" },
    { label: "重排", value: "未接入；超时须回退并记录降级" },
    { label: "最低证据门槛", value: "待校准；相似度不等于回答可信度" },
    { label: "切块策略", value: "待确认（T3）" },
  ],
};
