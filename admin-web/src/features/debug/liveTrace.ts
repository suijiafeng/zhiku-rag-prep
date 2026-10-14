import type { TraceResult } from "../../api/client";
import type { DebugTrace, Stage, StageKey } from "./fixtures";

const KEYS: readonly StageKey[] = ["rewrite", "vector", "lexical", "fusion", "rerank", "generation"];
const isKey = (k: string): k is StageKey => (KEYS as readonly string[]).includes(k);

/** Maps a server trace onto the page model. Only scores the server produced are shown; others stay null. */
export function traceFromResult(result: TraceResult, knowledgeBase: string, scopeVersion: string): DebugTrace {
  const stages: Stage[] = result.stages
    .filter((s) => isKey(s.key))
    .map((s) => ({ key: s.key as StageKey, name: s.name, status: s.status, elapsedMs: s.elapsedMs, summary: s.summary }));
  stages.push({ key: "generation", name: "上下文与生成", status: "skipped", elapsedMs: null, summary: "调试不调用模型" });
  return {
    traceId: result.traceId,
    question: result.question,
    knowledgeBase,
    scopeVersion,
    configVersion: "当前服务配置",
    stages,
    candidates: result.candidates.map((c) => ({
      chunk: `#${String(c.seq).padStart(3, "0")}`,
      document: c.title,
      version: c.versionLabel,
      vector: c.score,
      lexical: null,
      fused: null,
      rerank: null,
      finalRank: c.rank,
      usage: "none" as const,
    })),
    params: [
      { label: "检索方式", value: "授权范围内精确向量检索（基线）" },
      { label: "Embedding 模型 / 维度", value: `${result.embeddingModel} · ${result.dimension} 维` },
      { label: "融合", value: "未启用" },
      { label: "重排", value: "未接入" },
      { label: "最低证据门槛", value: "待校准；相似度不等于回答可信度" },
      { label: "查询时点", value: result.at },
    ],
  };
}
