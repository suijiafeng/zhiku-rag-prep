import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { CaseList, detailsBody, evidenceEntry, metricCards, missingForApproval, parseFacts, resultTag } from "./LiveEvaluation";
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

  it("counts cases left out of the denominators, naming source failures separately", () => {
    const cards = metricCards({ ...summary, sourceUnavailable: 2 }, 5);
    expect(cards[3]).toMatchObject({ title: "未计入分母", value: "3" });
    expect(cards[3]!.sub).toContain("来源失效 2");
    expect(metricCards(summary, 5)[3]!.value).toBe("1");
  });

  it("labels each result, including cases whose source is no longer eligible", () => {
    const r = { caseId: "c", question: "q", label: "answerable", mappingFailed: false, hit: false, evidenceHit: 0, evidenceTotal: 1, latencyMs: 0 };
    expect(resultTag({ ...r, sourceUnavailable: true })).toBe("来源已失效");
    expect(resultTag({ ...r, mappingFailed: true })).toBe("评测准备失败");
    expect(resultTag({ ...r, hit: true })).toBe("命中");
    expect(resultTag(r)).toBe("未命中");
  });

  it("does not flag approved cases that predate the sample fields", () => {
    const legacy: EvalCase = { id: "c4", question: "旧用例", label: "answerable", status: "approved", source: "manual", createdAt: "", originMessageId: null,
      expectedAnswer: null, requiredFacts: [], appliesTo: null, queryAt: null,
      evidence: [{ versionId: "v", documentTitle: "手册", versionLabel: "v1", startOffset: 0, endOffset: 4, quote: "摘录", available: true }] };
    const html = renderToStaticMarkup(<CaseList cases={[legacy]} onApprove={() => {}} onEdit={() => {}} />);
    expect(html).not.toContain("待补齐");
    expect(html).toContain("批准时未要求参考答案与必要事实");
  });

  it("lists what an answerable case still needs before approval", () => {
    const base = { id: "c", question: "q", status: "candidate", source: "manual", createdAt: "", originMessageId: null, appliesTo: null, queryAt: null } as const;
    expect(missingForApproval({ ...base, label: "answerable", evidence: [], expectedAnswer: null, requiredFacts: [] })).toEqual(["证据", "参考答案", "必要事实"]);
    expect(missingForApproval({ ...base, label: "handoff", evidence: [], expectedAnswer: null, requiredFacts: [] })).toEqual([]);
  });

  it("reads one required fact per line and builds the details request", () => {
    expect(parseFacts(" 10 年以上 15 天 \n\n按司龄计算\n")).toEqual(["10 年以上 15 天", "按司龄计算"]);
    expect(detailsBody({ expectedAnswer: " 按司龄 ", facts: "a\nb", appliesTo: "", queryAt: "" }))
      .toEqual({ expectedAnswer: "按司龄", requiredFacts: ["a", "b"] });
    expect(detailsBody({ expectedAnswer: "x", facts: "a", appliesTo: "正式员工", queryAt: "2099-06-01T08:00" }).queryAt)
      .toBe(new Date("2099-06-01T08:00").toISOString());
  });

  it("sends the chosen occurrence only when one was given", () => {
    expect(evidenceEntry("v", " 五个工作日 ", undefined)).toEqual({ versionId: "v", quote: "五个工作日" });
    expect(evidenceEntry("v", "五个工作日", 2)).toEqual({ versionId: "v", quote: "五个工作日", occurrence: 2 });
  });

  it("shows dashes before the first run", () => {
    expect(metricCards(undefined, 5).map((c) => c.value)).toEqual(["—", "—", "—", "—"]);
  });

  it("lets candidates be approved and completed but freezes approved cases", () => {
    const cases: EvalCase[] = [
      { id: "c1", question: "年假有几天?", label: "answerable", status: "candidate", source: "feedback", createdAt: "", evidence: [], originMessageId: "m1",
        expectedAnswer: null, requiredFacts: [], appliesTo: null, queryAt: null },
      { id: "c2", question: "报销多久?", label: "answerable", status: "approved", source: "manual", createdAt: "",
        evidence: [{ versionId: "v", documentTitle: "报销说明", versionLabel: "v2", startOffset: 0, endOffset: 4, quote: "五个工作日", available: true }],
        originMessageId: null, expectedAnswer: "五个工作日内到账", requiredFacts: ["五个工作日"], appliesTo: "正式员工", queryAt: null },
    ];
    const html = renderToStaticMarkup(<CaseList cases={cases} onApprove={() => {}} onEdit={() => {}} />);
    expect(html).toContain("候选");
    expect(html).toContain("待补齐：证据、参考答案、必要事实");
    expect(html).toContain("必要事实：五个工作日");
    expect(html).toContain("适用：正式员工");
    expect(html).toContain("五个工作日");
    expect((html.match(/批准/g) ?? []).length).toBe(1);
  });

  it("says a source was withdrawn or deleted instead of showing an empty quote", () => {
    const cases: EvalCase[] = [{ id: "c3", question: "差旅?", label: "answerable", status: "approved", source: "manual", createdAt: "", originMessageId: null,
      expectedAnswer: null, requiredFacts: [], appliesTo: null, queryAt: null,
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
