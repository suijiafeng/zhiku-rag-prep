import type { EvalRow } from "./fixtures";

export type EvalResult = "pass" | "fail" | "pending" | "prep-failed";

/** PRD ch.12: retrieval coverage, human verdict and citation support are judged separately; all must hold to pass. */
export function resultOf(r: EvalRow): EvalResult {
  if (r.mappingFailed) return "prep-failed";
  if (r.verdict === "pending") return "pending";
  return r.evidenceHit > 0 && r.verdict === "correct" && r.citationSupported !== false ? "pass" : "fail";
}

/** Nearest-rank percentile; null when there is nothing to measure. */
export function p95(values: number[]) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(0.95 * sorted.length) - 1]!;
}

const ratio = (num: number, den: number) => ({ num, den });

export function summarize(rows: EvalRow[]) {
  const evaluated = rows.filter((r) => !r.mappingFailed);
  const judged = evaluated.filter((r) => r.verdict !== "pending");
  const cited = evaluated.filter((r) => r.citationSupported !== null);
  return {
    evaluated: evaluated.length,
    prepFailed: rows.length - evaluated.length,
    passed: evaluated.filter((r) => resultOf(r) === "pass").length,
    hit: ratio(evaluated.filter((r) => r.evidenceHit > 0).length, evaluated.length),
    correct: ratio(judged.filter((r) => r.verdict === "correct").length, judged.length),
    citation: ratio(cited.filter((r) => r.citationSupported).length, cited.length),
    p95Ms: p95(evaluated.map((r) => r.latencyMs)),
  };
}

export const percent = ({ num, den }: { num: number; den: number }) => (den === 0 ? "—" : `${((num / den) * 100).toFixed(1)}%`);
