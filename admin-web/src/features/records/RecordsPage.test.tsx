import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RecordsPage, RecordsTable, praiseRate, topReasons } from "./RecordsPage";
import { FeedbackBar } from "../qa/FeedbackBar";
import type { RecordItem } from "../../api/client";

const items: RecordItem[] = [
  { messageId: "m1", createdAt: "2026-10-14T01:00:00Z", own: true, question: "年假有几天?", citationCount: 2, rating: "down",
    reason: "WRONG", comment: "不对", status: "open", rootCause: null, outcome: "complete" },
  { messageId: "m2", createdAt: "2026-10-14T02:00:00Z", own: false, question: null, citationCount: 1, rating: "up",
    reason: null, comment: null, status: "open", rootCause: null, outcome: "complete" },
  { messageId: "m3", createdAt: "2026-10-14T03:00:00Z", own: false, question: null, citationCount: 3, rating: null,
    reason: null, comment: null, status: null, rootCause: null, outcome: "complete" },
];

describe("records page", () => {
  it("reports praise rate as numerator over denominator", () => {
    expect(praiseRate({ total: 4, up: 3, down: 1, unrated: 0, reasons: {} })).toBe("3/4 · 75.0%");
    expect(praiseRate({ total: 2, up: 0, down: 0, unrated: 2, reasons: {} })).toBe("—");
  });

  it("orders dislike reasons by count", () => {
    expect(topReasons({ WRONG: 1, OUTDATED: 5, MISSING: 3, BAD_CITATION: 2 }).map((r) => r.key)).toEqual(["OUTDATED", "MISSING", "BAD_CITATION"]);
  });

  it("shows own text but hides other people's questions", () => {
    const html = renderToStaticMarkup(<RecordsTable items={items} onStatus={() => {}} onCandidate={() => {}} />);
    expect(html).toContain("年假有几天?");
    expect(html.match(/无权查看他人问答正文/g)).toHaveLength(2);
    expect(html).toContain("答错了");
  });

  it("offers the candidate action only on disliked rows", () => {
    const html = renderToStaticMarkup(<RecordsTable items={items} onStatus={() => {}} onCandidate={() => {}} />);
    expect((html.match(/加入评测候选/g) ?? []).length).toBe(1);
  });

  it("asks to connect instead of showing invented records", () => {
    const html = renderToStaticMarkup(<RecordsPage client={undefined} kbId={undefined} />);
    expect(html).toContain("连接本地服务");
    expect(html).not.toContain("张伟");
  });
});

describe("feedback bar", () => {
  it("marks the current rating and offers both choices", () => {
    const html = renderToStaticMarkup(<FeedbackBar current={{ rating: "up", reason: null, comment: null, version: 1, status: "open", updatedAt: "" }} onSubmit={async () => {}} />);
    expect(html).toContain("赞");
    expect(html).toContain("踩");
    expect(html).toContain("aria-pressed=\"true\"");
  });
});
