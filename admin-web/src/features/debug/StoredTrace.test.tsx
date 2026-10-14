import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { StoredTrace } from "../../api/client";
import { StoredTraceView } from "./StoredTrace";

const trace: StoredTrace = {
  traceId: "t1", origin: "chat", at: "2026-10-14T01:00:00Z", k: 5, embeddingModel: "bge-m3", dimension: 1024, messageId: "m1",
  createdAt: "2026-10-14T01:00:00Z",
  stages: [
    { key: "rewrite", name: "查询改写", status: "skipped", startedAt: null, endedAt: null, elapsedMs: null, summary: "未改写，保留原问题" },
    { key: "vector", name: "向量召回", status: "success", startedAt: "2026-10-14T01:00:00Z", endedAt: "2026-10-14T01:00:00.042Z", elapsedMs: 42, summary: "精确检索" },
    { key: "generation", name: "上下文与生成", status: "failed", startedAt: "2026-10-14T01:00:01Z", endedAt: "2026-10-14T01:00:02Z", elapsedMs: 1000, summary: "失败：MODEL_UNAVAILABLE" },
  ],
  candidates: [
    { rank: 1, chunkId: "c1", versionId: "v1", documentTitle: "员工手册", score: 0.8123 },
    { rank: 2, chunkId: "c2", versionId: "v2", documentTitle: null, score: 0.5 },
  ],
};

describe("stored trace", () => {
  it("shows every stage with its status and timing, and candidates without any text", () => {
    const html = renderToStaticMarkup(<StoredTraceView trace={trace} />);
    expect(html).toContain("问答");
    expect(html).toContain("向量召回");
    expect(html).toContain("42 ms");
    expect(html).toContain("失败：MODEL_UNAVAILABLE");
    expect(html).toContain("0.8123");
    expect(html).toContain("员工手册");
    expect(html).toContain("文档已删除");
  });
});
