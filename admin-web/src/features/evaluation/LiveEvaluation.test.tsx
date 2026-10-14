import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { CaseList, metricCards } from "./LiveEvaluation";
import { RecordsTable } from "../records/RecordsPage";
import type { EvalCase, EvalSummary } from "../../api/client";

const summary: EvalSummary = {
  cases: 4, evaluated: 3, prepFailed: 1, notApplicable: 0, hit: { num: 2, den: 3 }, recall: { num: 3, den: 5 }, recallMacro: 0.5, p95Ms: 42,
};

describe("live evaluation", () => {
  it("reports measured ratios and never an answer-accuracy figure", () => {
    const cards = metricCards(summary, 5);
    expect(cards.map((c) => c.value)).toEqual(["2/3", "3/5", "42 ms", "1"]);
    expect(cards[0]!.title).toBe("Hit@5（证据位置覆盖）");
    expect(cards.map((c) => c.title).join()).not.toContain("答案准确率");
  });

  it("shows dashes before the first run", () => {
    expect(metricCards(undefined, 5).map((c) => c.value)).toEqual(["—", "—", "—", "—"]);
  });

  it("lets candidates be approved and completed but freezes approved cases", () => {
    const cases: EvalCase[] = [
      { id: "c1", question: "年假有几天?", label: "answerable", status: "candidate", source: "feedback", createdAt: "", evidence: [], originMessageId: "m1" },
      { id: "c2", question: "报销多久?", label: "answerable", status: "approved", source: "manual", createdAt: "",
        evidence: [{ versionId: "v", documentTitle: "报销说明", versionLabel: "v2", startOffset: 0, endOffset: 4, quote: "五个工作日", available: true }],
        originMessageId: null },
    ];
    const html = renderToStaticMarkup(<CaseList cases={cases} onApprove={() => {}} onEdit={() => {}} />);
    expect(html).toContain("候选");
    expect(html).toContain("待补证据");
    expect(html).toContain("五个工作日");
    expect((html.match(/批准/g) ?? []).length).toBe(1);
  });

  it("says a source was withdrawn or deleted instead of showing an empty quote", () => {
    const cases: EvalCase[] = [{ id: "c3", question: "差旅?", label: "answerable", status: "approved", source: "manual", createdAt: "", originMessageId: null,
      evidence: [{ versionId: "v", documentTitle: "差旅标准", versionLabel: "v1", startOffset: 3, endOffset: 9, quote: null, available: false }] }];
    const html = renderToStaticMarkup(<CaseList cases={cases} onApprove={() => {}} onEdit={() => {}} />);
    expect(html).toContain("来源已撤回或删除，摘录不再显示");
    expect(html).not.toContain("「");
  });
});

describe("records to evaluation candidates", () => {
  it("enables the candidate action only for visible disliked questions", () => {
    const html = renderToStaticMarkup(<RecordsTable onStatus={() => {}} onClose={() => {}} onHistory={() => {}} onCandidate={() => {}} items={[
      { messageId: "a", createdAt: "2026-10-14T00:00:00Z", own: true, question: "年假?", citationCount: 1, rating: "down", reason: "WRONG", comment: null, status: "open", rootCause: null, resolution: null, verificationCaseId: null, outcome: "complete", sourceAvailable: true },
      { messageId: "b", createdAt: "2026-10-14T00:00:00Z", own: false, question: null, citationCount: 1, rating: "down", reason: "WRONG", comment: null, status: "open", rootCause: null, resolution: null, verificationCaseId: null, outcome: "complete", sourceAvailable: true },
    ]} />);
    const buttons = html.match(/<button[^>]*>(?:(?!<\/button>).)*加入评测候选/gs) ?? [];
    expect(buttons).toHaveLength(2);
    expect(buttons.filter((b) => /disabled/.test(b))).toHaveLength(1);
  });
});
