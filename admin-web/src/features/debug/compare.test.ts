import { describe, expect, it } from "vitest";
import type { TraceResult } from "../../api/client";
import { compareRuns, configLabel } from "./compare";

const cand = (chunkId: string, rank: number) => ({ chunkId, documentId: "d", title: "手册", versionId: "v", versionLabel: "v1", seq: rank, heading: null,
  startOffset: 0, endOffset: 1, text: "", score: 0.9, rank });
const run = (configVersion: number, ids: string[]): TraceResult => ({ traceId: `t${configVersion}`, question: "年假?", at: "2026-10-15T00:00:00Z",
  embeddingModel: "m", dimension: 4, configVersion, stages: [], candidates: ids.map((id, i) => cand(id, i + 1)) });

describe("compareRuns", () => {
  it("aligns candidates by chunk and marks the ones only one side found", () => {
    const rows = compareRuns(run(1, ["a", "b"]), run(2, ["b", "c", "a"]));
    expect(rows.map((r) => [r.chunkId, r.baselineRank, r.rerunRank])).toEqual([["a", 1, 3], ["b", 2, 1], ["c", null, 2]]);
    expect(compareRuns(run(1, ["a", "b"]), run(2, ["a"])).find((r) => r.chunkId === "b")?.rerunRank).toBeNull();
  });

  it("names deployment defaults and versions", () => {
    expect(configLabel(0)).toBe("部署默认值");
    expect(configLabel(3)).toBe("配置版本 3");
  });
});
