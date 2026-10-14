import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { OverviewPage } from "./OverviewPage";
import { overviewFixture } from "./fixtures";

describe("overview page", () => {
  const html = renderToStaticMarkup(<OverviewPage data={overviewFixture} />);

  it("shows every fixture metric, document and question", () => {
    for (const m of overviewFixture.metrics) expect(html).toContain(m.title);
    for (const d of overviewFixture.recentDocuments) expect(html).toContain(d.name);
    for (const q of overviewFixture.recentQuestions) expect(html).toContain(q.question);
  });

  it("does not invent vector store decisions pending ADR-002", () => {
    expect(html).not.toContain("1024");
    expect(html).not.toContain("HNSW");
    expect(html).toContain("待确认");
  });

  it("renders one bar per day with an accessible label", () => {
    const bars = html.match(/role="img"/g) ?? [];
    expect(bars).toHaveLength(overviewFixture.dailyQuestions.length);
  });

  it("escapes untrusted document names", () => {
    const hostile = { ...overviewFixture, recentDocuments: [{ ...overviewFixture.recentDocuments[0]!, name: "<img src=x>" }] };
    const out = renderToStaticMarkup(<OverviewPage data={hostile} />);
    expect(out).not.toContain("<img src=x>");
  });
});
