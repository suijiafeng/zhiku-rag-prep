import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { overviewFromApi } from "./overviewMapper";
import { OverviewPage } from "./OverviewPage";
import type { OverviewApi } from "../../api/client";

const api: OverviewApi = {
  documents: 2, versions: 3, readyVersions: 1, chunks: 12, vectors: 12, questionsToday: 4, questionsYesterday: 6,
  feedback: { up: 3, down: 1 },
  daily: [0, 1, 2, 3, 4, 6, 4].map((count, i) => ({ day: `2026-10-${String(8 + i).padStart(2, "0")}`, count })),
  embeddingSpaces: [{ model: "ollama:bge-m3", dimension: 1024, vectors: 12 }],
  lastIndexedAt: "2026-10-14T05:00:00Z",
  recentDocuments: [{ documentId: "d", title: "员工手册", versionLabel: "v3.2", indexStatus: "ready", chunks: 12, createdAt: "2026-10-14T01:00:00Z" }],
  recentQuestions: [
    { messageId: "m1", own: true, question: "年假有几天?", citationCount: 2, createdAt: "2026-10-14T02:00:00Z" },
    { messageId: "m2", own: false, question: null, citationCount: 1, createdAt: "2026-10-14T03:00:00Z" },
  ],
};

describe("live overview", () => {
  const data = overviewFromApi(api, "Asia/Shanghai", new Date("2026-10-14T06:30:00Z"));

  it("maps real counts and does not invent an evaluation metric", () => {
    expect(data.metrics.map((m) => m.value)).toEqual(["2", "12", "4", "3/4 · 75.0%"]);
    expect(data.metrics[2]).toMatchObject({ delta: "昨日 6", trend: "down" });
    expect(data.metrics.map((m) => m.title)).not.toContain("回答率@5");
  });

  it("shows the real embedding space and exact search", () => {
    const rows = Object.fromEntries(data.vectorStore.map((r) => [r.label, r.value]));
    expect(rows["向量模型"]).toBe("ollama:bge-m3");
    expect(rows["向量维度"]).toBe("1024");
    expect(rows["索引类型"]).toContain("精确检索");
    expect(rows["最近同步"]).toBe("2026-10-14 13:00");
  });

  it("keeps other people's questions hidden and labels the page as live", () => {
    expect(data.recentQuestions[1]!.question).toContain("正文不可见");
    const html = renderToStaticMarkup(<OverviewPage data={data} live onNewQuestion={() => {}} />);
    expect(html).toContain("实时数据");
    expect(html).not.toContain("示例数据");
    expect(html).not.toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*新建问答/s);
  });

  it("reports missing vectors honestly", () => {
    const empty = overviewFromApi({ ...api, embeddingSpaces: [], vectors: 0, lastIndexedAt: null, feedback: { up: 0, down: 0 } }, "Asia/Shanghai", new Date());
    const rows = Object.fromEntries(empty.vectorStore.map((r) => [r.label, r.value]));
    expect(rows["向量模型"]).toContain("暂无向量");
    expect(empty.metrics[3]!.value).toBe("—");
  });
});
