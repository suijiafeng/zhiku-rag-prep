import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { OutcomeNote, OUTCOME_LABEL } from "./outcome";
import { RecordsTable } from "../records/RecordsPage";

describe("turn outcomes", () => {
  it("labels every stored outcome and never shows a draft", () => {
    expect(OUTCOME_LABEL).toEqual({ complete: "已完成", refused: "已拒答", cancelled: "已中断", failed: "失败" });
    const html = renderToStaticMarkup(<OutcomeNote status="failed" errorCode="MODEL_UNAVAILABLE" />);
    expect(html).toContain("失败");
    expect(html).toContain("MODEL_UNAVAILABLE");
    expect(html).toContain("未保存回答内容");
  });

  it("shows the outcome in Q&A records", () => {
    const html = renderToStaticMarkup(<RecordsTable onStatus={() => {}} onClose={() => {}} onHistory={() => {}} onCandidate={() => {}} items={[
      { messageId: "a", createdAt: "2026-10-14T00:00:00Z", own: true, question: "年假?", citationCount: 0, rating: null, reason: null,
        comment: null, status: null, rootCause: null, resolution: null, verificationCaseId: null, outcome: "refused", sourceAvailable: null },
    ]} />);
    expect(html).toContain("已拒答");
  });
});
