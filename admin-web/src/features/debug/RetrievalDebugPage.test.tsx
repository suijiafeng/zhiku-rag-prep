import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RetrievalDebugPage, totalElapsedMs } from "./RetrievalDebugPage";
import { debugFixture, type ScoreKey } from "./fixtures";

const trace = debugFixture;
const html = renderToStaticMarkup(<RetrievalDebugPage trace={trace} />);

describe("retrieval debug page", () => {
  it("shows the six PRD stages in order", () => {
    const names = ["查询改写", "向量召回", "词法召回", "融合排序", "重排", "上下文与生成"];
    expect(trace.stages.map((s) => s.name)).toEqual(names);
    let last = -1;
    for (const n of names) {
      const at = html.indexOf(n);
      expect(at).toBeGreaterThan(last);
      last = at;
    }
  });

  it("sums elapsed time only over stages that ran", () => {
    const ran = trace.stages.filter((s) => s.elapsedMs !== null).reduce((a, s) => a + (s.elapsedMs ?? 0), 0);
    expect(totalElapsedMs(trace.stages)).toBe(ran);
    expect(totalElapsedMs([{ ...trace.stages[0]!, elapsedMs: null }])).toBe(0);
  });

  it("never shows scores for a skipped stage", () => {
    const byStage: Record<string, ScoreKey> = { lexical: "lexical", fusion: "fused", rerank: "rerank" };
    for (const stage of trace.stages.filter((s) => s.status === "skipped")) {
      const key = byStage[stage.key];
      if (key) expect(trace.candidates.every((c) => c[key] === null)).toBe(true);
    }
    expect(html).toContain("跳过");
  });

  it("does not present undecided retrieval choices as configured", () => {
    for (const claim of ["HNSW", "ES", "α=", "0.7 ×", "bge-reranker", "< 0.5"]) expect(html).not.toContain(claim);
    expect(html).toContain("精确检索");
    expect(html).toContain("待校准");
  });

  it("shows query rewrite context required by the PRD", () => {
    expect(html).toContain(trace.question);
    expect(html).toContain(trace.knowledgeBase);
    expect(html).toContain(trace.scopeVersion);
  });

  it("marks cited candidates and states that only authorized candidates are shown", () => {
    const cited = trace.candidates.filter((c) => c.usage === "cited").length;
    expect(html.match(/class="[^"]*row-cited/g) ?? []).toHaveLength(cited);
    expect(html).toContain("仅显示授权范围内候选");
  });

  it("labels the trace as sample data", () => {
    expect(html).toContain("示例轨迹");
  });
});
