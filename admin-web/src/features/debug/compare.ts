import type { TraceResult } from "../../api/client";

export interface CompareRow {
  chunkId: string;
  title: string;
  versionLabel: string;
  seq: number;
  baselineRank: number | null;
  rerunRank: number | null;
}

/** 同一问题两次检索的候选对比：按切块对齐，标出只在一侧出现的候选。只比较检索结果，不涉及回答质量。 */
export function compareRuns(baseline: TraceResult, rerun: TraceResult): CompareRow[] {
  const rows = new Map<string, CompareRow>();
  for (const c of baseline.candidates) {
    rows.set(c.chunkId, { chunkId: c.chunkId, title: c.title, versionLabel: c.versionLabel, seq: c.seq, baselineRank: c.rank, rerunRank: null });
  }
  for (const c of rerun.candidates) {
    const row = rows.get(c.chunkId);
    if (row) row.rerunRank = c.rank;
    else rows.set(c.chunkId, { chunkId: c.chunkId, title: c.title, versionLabel: c.versionLabel, seq: c.seq, baselineRank: null, rerunRank: c.rank });
  }
  const order = (r: CompareRow) => Math.min(r.baselineRank ?? Infinity, r.rerunRank ?? Infinity);
  return [...rows.values()].sort((a, b) => order(a) - order(b));
}

export function configLabel(version: number) {
  return version === 0 ? "部署默认值" : `配置版本 ${version}`;
}
