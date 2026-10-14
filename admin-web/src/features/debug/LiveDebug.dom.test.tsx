// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient, ConfigOverview, TraceResult } from "../../api/client";
import { LiveDebug } from "./LiveDebug";

const cand = (chunkId: string, title: string, rank: number) => ({ chunkId, documentId: "d", title, versionId: "v", versionLabel: "v1", seq: rank,
  heading: null, startOffset: 0, endOffset: 1, text: "", score: 0.9, rank });
const overview = { published: null, candidate: null, history: [], defaults: { retrievalK: 8, contextChunks: 5, handoffChannel: "", maxContextChunks: 10 } } as unknown as ConfigOverview;

afterEach(cleanup);

describe("LiveDebug re-run", () => {
  it("keeps the previous run of the same question as the baseline and shows what changed", async () => {
    const results: TraceResult[] = [
      { traceId: "t1", question: "年假有几天", at: "2026-10-15T00:00:00Z", embeddingModel: "m", dimension: 4, configVersion: 0, stages: [],
        candidates: [cand("a", "员工手册", 1)] },
      { traceId: "t2", question: "年假有几天", at: "2026-10-15T00:00:00Z", embeddingModel: "m", dimension: 4, configVersion: 0, stages: [],
        candidates: [cand("a", "员工手册", 1), cand("b", "差旅标准", 2)] },
    ];
    const retrievalDebug = vi.fn(async () => results.shift()!);
    const client = { config: vi.fn(async () => overview), retrievalDebug } as unknown as ApiClient;
    render(<LiveDebug client={client} kbId="kb1" kbName="制度库" />);
    fireEvent.change(screen.getByLabelText("调试问题"), { target: { value: "年假有几天" } });
    fireEvent.click(screen.getByRole("button", { name: /运\s*行\s*检\s*索/ }));
    const rerun = await screen.findByRole("button", { name: /再\s*运\s*行/ });
    // 第一次运行结束后按钮才可再次点击（运行中为加载态）。
    await waitFor(() => expect(rerun.className).not.toContain("ant-btn-loading"));
    expect(screen.queryByText(/对比 · 基线/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /再\s*运\s*行/ }));
    await waitFor(() => expect(screen.getByText(/对比 · 基线/)).toBeTruthy());
    expect(screen.getByText("新增")).toBeTruthy();
    expect(retrievalDebug).toHaveBeenCalledTimes(2);
  });
});
