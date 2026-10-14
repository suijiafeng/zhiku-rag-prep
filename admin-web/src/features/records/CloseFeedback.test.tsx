import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { closingBody, HandlingList, verificationCandidates } from "./CloseFeedback";
import type { EvalCase } from "../../api/client";

describe("closing feedback", () => {
  it("requires root cause, fix and an approved verification case to resolve", () => {
    expect(closingBody("resolved", { rootCause: undefined, resolution: "已修复", caseId: "c1" })).toEqual({ error: "请选择根因" });
    expect(closingBody("resolved", { rootCause: "解析/切块遗漏", resolution: "  ", caseId: "c1" })).toEqual({ error: "请填写修复方案" });
    expect(closingBody("resolved", { rootCause: "解析/切块遗漏", resolution: "重新切块", caseId: undefined })).toEqual({ error: "请选择验证用例" });
    expect(closingBody("resolved", { rootCause: "解析/切块遗漏", resolution: " 重新切块 ", caseId: "c1" })).toEqual({
      body: { status: "resolved", rootCause: "解析/切块遗漏", resolution: "重新切块", verificationCaseId: "c1" },
    });
  });

  it("requires a reason to close without a fix", () => {
    expect(closingBody("wont_fix", { rootCause: undefined, resolution: "", caseId: undefined })).toEqual({ error: "请填写不处理的理由" });
    expect(closingBody("wont_fix", { rootCause: undefined, resolution: "超出试点范围", caseId: "c1" })).toEqual({
      body: { status: "wont_fix", resolution: "超出试点范围" },
    });
  });

  it("lists handling changes in order with their basis", () => {
    const html = renderToStaticMarkup(<HandlingList entries={[
      { status: "in_progress", rootCause: "解析/切块遗漏", resolution: null, verificationCaseId: null, changedBy: "u1", changedAt: "2026-10-14T01:00:00Z", feedbackVersion: 1 },
      { status: "wont_fix", rootCause: null, resolution: "资料未覆盖", verificationCaseId: null, changedBy: "u1", changedAt: "2026-10-14T02:00:00Z", feedbackVersion: 2 },
    ]} />);
    expect(html.indexOf("处理中")).toBeLessThan(html.indexOf("无需处理"));
    expect(html).toContain("资料未覆盖");
    expect(html).toContain("反馈第 2 版");
    expect(renderToStaticMarkup(<HandlingList entries={[]} />)).toContain("暂无处理记录");
  });

  it("offers only approved cases made from this feedback as verification", () => {
    const base = { label: "answerable" as const, source: "feedback", createdAt: "", evidence: [], expectedAnswer: null, requiredFacts: [], appliesTo: null, queryAt: null };
    const cases: EvalCase[] = [
      { ...base, id: "own", question: "a", status: "approved", originMessageId: "m1" },
      { ...base, id: "draft", question: "b", status: "candidate", originMessageId: "m1" },
      { ...base, id: "other", question: "c", status: "approved", originMessageId: "m2" },
      { ...base, id: "manual", question: "d", status: "approved", originMessageId: null },
    ];
    expect(verificationCandidates(cases, "m1").map((c) => c.id)).toEqual(["own"]);
  });
});
