// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ApiClient, type EvalCase } from "../../api/client";
import { LiveEvaluation } from "./LiveEvaluation";

const candidate: EvalCase = {
  id: "c", question: "虚构跨文档问题", label: "answerable", status: "candidate", source: "manual",
  createdAt: "2030-01-01T00:00:00Z", originMessageId: null, expectedAnswer: "中性答案", requiredFacts: ["条件甲", "条件乙"],
  appliesTo: null, queryAt: null, sourceAvailable: true,
  evidence: [
    { versionId: "v1", documentTitle: "文档甲", versionLabel: "v1", startOffset: 12, endOffset: 15, quote: "条件甲", available: true },
    { versionId: "v2", documentTitle: "文档乙", versionLabel: "v2", startOffset: 24, endOffset: 27, quote: "条件乙", available: true },
  ],
};
const documents = ["v1", "v2", "v3"].map((id) => ({ id, title: `文档${id}`, createdAt: "2030-01-01T00:00:00Z", versions: [{
  id, versionLabel: id, effectiveFrom: "2030-01-01T00:00:00Z", effectiveUntil: null, approved: true, published: true,
  withdrawn: false, superseded: false, indexStatus: "ready" as const, indexError: null,
}] }));
function clientWith(overrides: Partial<ApiClient> = {}): ApiClient {
  return { evalCases: vi.fn(async () => [candidate]), latestRun: vi.fn(async () => undefined), documents: vi.fn(async () => documents),
    setEvalEvidence: vi.fn(async () => {}), setEvalCaseDetails: vi.fn(async () => {}), createEvalCase: vi.fn(async () => ({ id: "new" })),
    ...overrides } as unknown as ApiClient;
}
const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });
afterEach(cleanup);

async function edit(client: ApiClient) {
  render(<LiveEvaluation client={client} kbId="kb" />);
  fireEvent.click(await screen.findByRole("button", named("补齐")));
  await screen.findByRole("button", named("保存"));
}
async function select(label: string, text: string) {
  fireEvent.mouseDown(screen.getByLabelText(label));
  fireEvent.click(await screen.findByText(text, { selector: ".ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option-content" }));
}

describe("evaluation evidence interactions", () => {
  it("preserves the other evidence and its exact location when editing one excerpt", async () => {
    const client = clientWith();
    await edit(client);
    expect((screen.getByLabelText("原文摘录 2") as HTMLTextAreaElement).value).toBe("条件乙");
    fireEvent.change(screen.getByLabelText("原文摘录 1"), { target: { value: "新的条件甲" } });
    fireEvent.click(screen.getByRole("button", named("保存")));
    await waitFor(() => expect(client.setEvalEvidence).toHaveBeenCalledWith("kb", "c", [
      { versionId: "v1", quote: "新的条件甲" }, { versionId: "v2", quote: "条件乙", startOffset: 24 },
    ]));
  });

  it("adds evidence across documents and removes only the selected entry", async () => {
    const client = clientWith();
    await edit(client);
    fireEvent.click(screen.getByRole("button", named("新增证据")));
    await select("证据版本 3", "文档v3 v3");
    fireEvent.change(screen.getByLabelText("原文摘录 3"), { target: { value: "条件丙" } });
    fireEvent.click(screen.getByRole("button", { name: "删除证据 1" }));
    fireEvent.click(screen.getByRole("button", named("保存")));
    await waitFor(() => expect(client.setEvalEvidence).toHaveBeenCalledWith("kb", "c", [
      { versionId: "v2", quote: "条件乙", startOffset: 24 }, { versionId: "v3", quote: "条件丙" },
    ]));
  });

  it("creates a case with evidence from two documents", async () => {
    const client = clientWith({ evalCases: vi.fn(async () => []) });
    render(<LiveEvaluation client={client} kbId="kb" />);
    await screen.findByText("暂无评测用例");
    fireEvent.change(screen.getByLabelText("评测问题"), { target: { value: "虚构多来源问题" } });
    for (const i of [1, 2]) {
      fireEvent.click(screen.getByRole("button", named("新增证据")));
      await select(`证据版本 ${i}`, `文档v${i} v${i}`);
      fireEvent.change(screen.getByLabelText(`原文摘录 ${i}`), { target: { value: `中性条件${i}` } });
    }
    fireEvent.click(screen.getByRole("button", { name: "创建候选" }));
    await waitFor(() => expect(client.createEvalCase).toHaveBeenCalledWith("kb", expect.objectContaining({ evidence: [
      { versionId: "v1", quote: "中性条件1" }, { versionId: "v2", quote: "中性条件2" },
    ] })));
  });

  it("submits only once while pending and permits recovery after an explicit API failure", async () => {
    let reject!: (e: unknown) => void;
    const create = vi.fn().mockImplementationOnce(() => new Promise((_, r) => { reject = r; })).mockResolvedValue({ id: "new" });
    const client = clientWith({ evalCases: vi.fn(async () => []), createEvalCase: create });
    render(<LiveEvaluation client={client} kbId="kb" />);
    await screen.findByText("暂无评测用例");
    fireEvent.change(screen.getByLabelText("评测问题"), { target: { value: "虚构转交问题" } });
    await select("标签", "应转人工");
    const button = screen.getByRole("button", { name: "创建候选" });
    fireEvent.click(button); fireEvent.click(button);
    expect(create).toHaveBeenCalledTimes(1);
    expect((button as HTMLButtonElement).disabled).toBe(true);
    reject(new ApiError(422, "UNPROCESSABLE", "请核对样本字段"));
    await screen.findByText("请核对样本字段");
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
    expect((screen.getByLabelText("评测问题") as HTMLInputElement).value).toBe("虚构转交问题");
    fireEvent.click(button);
    await waitFor(() => expect(create).toHaveBeenCalledTimes(2));
  });
});
