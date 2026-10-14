import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { IndexStatusTag } from "../../components/IndexStatusTag";
import { businessDate, deletionLabel, indexState, shouldPoll } from "./LiveDocuments";
import type { DocumentInfo, VersionInfo } from "../../api/client";

const version = (indexStatus: VersionInfo["indexStatus"]): VersionInfo => ({
  id: "v", versionLabel: "v1", effectiveFrom: "2025-12-31T16:00:00Z", effectiveUntil: null, approved: false, published: false,
  withdrawn: false, superseded: false, indexStatus, indexError: null,
});
const docs = (...states: VersionInfo["indexStatus"][]): DocumentInfo[] => [{ id: "d", title: "t", createdAt: "", versions: states.map(version) }];

describe("live documents", () => {
  it("does not call a version without content 'parsing'", () => {
    expect(indexState("pending")).toBe("pending");
    expect(indexState("processing")).toBe("parsing");
    expect(indexState("ready")).toBe("indexed");
    expect(renderToStaticMarkup(<IndexStatusTag status="pending" />)).toContain("待处理");
  });

  it("polls only while watching an upload or reindex that is still in progress", () => {
    expect(shouldPoll(docs("pending"), 0)).toBe(false);
    expect(shouldPoll(docs("processing"), 0)).toBe(true);
    expect(shouldPoll(docs("pending"), 3)).toBe(true);
    expect(shouldPoll(docs("ready"), 3)).toBe(false);
  });

  it("keeps polling a requested deletion only while watching and cleanup has not finished", () => {
    const deleted = (status: "pending" | "running" | "done" | "failed"): DocumentInfo[] =>
      [{ id: "d", title: "t", createdAt: "", versions: [], deletion: { status, deletedAt: "2026-01-01T00:00:00Z", error: null } }];
    expect(shouldPoll(deleted("pending"), 3)).toBe(true);
    expect(shouldPoll(deleted("running"), 3)).toBe(true);
    expect(shouldPoll(deleted("pending"), 0)).toBe(false);
    expect(shouldPoll(deleted("done"), 3)).toBe(false);
    expect(shouldPoll(deleted("failed"), 3)).toBe(false);
  });

  it("states that access stopped before cleanup finished", () => {
    expect(deletionLabel("pending")).toBe("访问已停用 · 清理进行中");
    expect(deletionLabel("running")).toBe("访问已停用 · 清理进行中");
    expect(deletionLabel("done")).toBe("已删除 · 清理完成");
    expect(deletionLabel("failed")).toBe("访问已停用 · 清理失败");
  });

  it("shows business dates in the configured zone", () => {
    expect(businessDate("2025-12-31T16:00:00Z", "Asia/Shanghai")).toBe("2026-01-01");
    expect(businessDate(null, "Asia/Shanghai")).toBeUndefined();
  });
});
