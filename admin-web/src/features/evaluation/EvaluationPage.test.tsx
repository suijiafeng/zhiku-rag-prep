import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { EvaluationPage } from "./EvaluationPage";
import { evaluationFixture, type EvalRow } from "./fixtures";
import { p95, resultOf, summarize } from "./metrics";

const rows = evaluationFixture.rows;
const html = renderToStaticMarkup(<EvaluationPage data={evaluationFixture} />);

const row = (patch: Partial<EvalRow>): EvalRow => ({ ...rows[0]!, ...patch });

describe("evaluation metrics", () => {
  it("passes only with evidence hit, correct human verdict and supported citations", () => {
    expect(resultOf(row({}))).toBe("pass");
    expect(resultOf(row({ evidenceHit: 0 }))).toBe("fail");
    expect(resultOf(row({ verdict: "incorrect" }))).toBe("fail");
    expect(resultOf(row({ citationSupported: false }))).toBe("fail");
    expect(resultOf(row({ verdict: "pending" }))).toBe("pending");
    expect(resultOf(row({ mappingFailed: true, evidenceHit: 0 }))).toBe("prep-failed");
  });

  it("excludes preparation failures from every denominator", () => {
    const s = summarize(rows);
    const evaluated = rows.filter((r) => !r.mappingFailed);
    expect(s.evaluated).toBe(evaluated.length);
    expect(s.prepFailed).toBe(rows.length - evaluated.length);
    expect(s.hit.den).toBe(evaluated.length);
    expect(s.hit.num).toBe(evaluated.filter((r) => r.evidenceHit > 0).length);
    expect(s.correct.num).toBe(evaluated.filter((r) => r.verdict === "correct").length);
  });

  it("computes nearest-rank P95", () => {
    expect(p95([100])).toBe(100);
    expect(p95([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])).toBe(19);
    expect(p95([])).toBeNull();
  });
});

describe("evaluation page", () => {
  it("reports numerator and denominator, not bare percentages", () => {
    const s = summarize(rows);
    expect(html).toContain(`${s.hit.num}/${s.hit.den}`);
    expect(html).toContain(`${s.correct.num}/${s.correct.den}`);
    expect(html).toContain("试点");
  });

  it("lists every question and a separate preparation-failure state", () => {
    for (const r of rows) expect(html).toContain(r.question);
    expect(html).toContain("评测准备失败");
  });

  it("only uses PRD root-cause categories and never defaults to the model", () => {
    for (const r of rows.filter((x) => x.rootCause)) expect(html).toContain(r.rootCause!);
    expect(html).not.toContain("模型问题");
  });

  it("does not let feedback or config changes skip review", () => {
    expect(html).not.toContain("确认入库");
    expect(html).not.toContain("确认应用");
    expect(html).not.toContain("BM25 权重");
    expect(html).toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*补齐真值后审批/s);
    expect(html).toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*运行评测/s);
  });
});
