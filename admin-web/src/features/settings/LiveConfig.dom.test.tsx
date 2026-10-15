// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ApiClient, type ConfigOverview, type KnowledgeBaseConfig } from "../../api/client";
import { LiveConfig } from "./LiveConfig";

const validated: KnowledgeBaseConfig = {
  id: "c1", version: null, status: "candidate", revision: 1, retrievalK: 10, contextChunks: 4, handoffChannel: null, promptAddendum: null,
  note: null, createdBy: "u1", createdAt: "2026-10-14T01:00:00Z", validatedAt: "2026-10-14T02:00:00Z",
  validation: { evaluationRunId: "r", cases: 2, humanQualityChecked: false }, publishedBy: null, publishedAt: null, rolledBackFrom: null,
};
const overview: ConfigOverview = { published: null, candidate: validated, history: [], defaults: { retrievalK: 8, contextChunks: 5, handoffChannel: "", maxContextChunks: 10 } };

function clientWith(overrides: Partial<ApiClient>): ApiClient {
  return {
    config: vi.fn(async () => overview),
    saveConfigCandidate: vi.fn(async () => validated),
    configAction: vi.fn(async () => validated),
    rollbackConfig: vi.fn(async () => validated),
    ...overrides,
  } as unknown as ApiClient;
}

afterEach(cleanup);

// antd 会在两个汉字的按钮文字中间插入空格，按名称查找时允许空白。
const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });

describe("LiveConfig in a DOM", () => {
  it("blocks validate and publish while the form has unsaved changes, and allows them again after saving", async () => {
    const client = clientWith({});
    render(<LiveConfig client={client} kbId="kb1" />);
    const publish = await screen.findByRole("button", named("发布"));
    expect((publish as HTMLButtonElement).disabled).toBe(false);

    fireEvent.change(screen.getByLabelText("召回 K"), { target: { value: "20" } });
    await waitFor(() => expect(screen.getByText("未保存")).toBeTruthy());
    expect((screen.getByRole("button", named("发布")) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", named("验证")) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", named("发布")));
    expect(client.configAction).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", named("保存候选")));
    await waitFor(() => expect(client.saveConfigCandidate).toHaveBeenCalledWith("kb1", expect.objectContaining({ retrievalK: 20, expectedRevision: 1 })));
    await waitFor(() => expect(screen.queryByText("未保存")).toBeNull());
  });

  it("keeps unsaved edits when saving fails", async () => {
    const client = clientWith({ saveConfigCandidate: vi.fn(async () => { throw new ApiError(503, "UNAVAILABLE", "服务暂不可用", "req-2"); }) });
    render(<LiveConfig client={client} kbId="kb1" />);
    await screen.findByRole("button", named("发布"));
    fireEvent.change(screen.getByLabelText("召回 K"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", named("保存候选")));
    await waitFor(() => expect(client.config).toHaveBeenCalledTimes(2));
    expect((screen.getByLabelText("召回 K") as HTMLInputElement).value).toBe("20");
    expect(screen.getByText("未保存")).toBeTruthy();
  });

  it("keeps unsaved edits on a revision conflict and compares them against the reloaded candidate", async () => {
    const newer = { ...overview, candidate: { ...validated, revision: 2, retrievalK: 12 } };
    const config = vi.fn().mockResolvedValueOnce(overview).mockResolvedValue(newer);
    const client = clientWith({ config, saveConfigCandidate: vi.fn(async () => { throw new ApiError(409, "CONFIG_CONFLICT", "冲突", "req-3"); }) });
    render(<LiveConfig client={client} kbId="kb1" />);
    await screen.findByRole("button", named("发布"));
    fireEvent.change(screen.getByLabelText("召回 K"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", named("保存候选")));
    expect(await screen.findByText(/你的修改仍保留在表单中/)).toBeTruthy();
    expect((screen.getByLabelText("召回 K") as HTMLInputElement).value).toBe("20");
    expect(screen.getByText("未保存")).toBeTruthy();
  });

  it("shows the reason and a retry when the first load fails", async () => {
    const config = vi.fn()
      .mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", "没有配置权限", "req-1"))
      .mockResolvedValueOnce(overview);
    const client = clientWith({ config });
    render(<LiveConfig client={client} kbId="kb1" />);
    expect(await screen.findByText("配置加载失败")).toBeTruthy();
    expect(screen.getByText(/FORBIDDEN/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", named("重试")));
    expect(await screen.findByRole("button", named("发布"))).toBeTruthy();
    expect(config).toHaveBeenCalledTimes(2);
  });
});
