// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient, Conversation, HistoryMessage } from "../../api/client";
import { LiveQa } from "./LiveQa";

function clientWith(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    conversations: vi.fn(async () => []),
    messages: vi.fn(async () => []),
    // 在途请求：始终不结束
    raw: vi.fn(() => new Promise<Response>(() => {})),
    ...overrides,
  } as unknown as ApiClient;
}
afterEach(cleanup);

const conversation: Conversation = { id: "conv", kbId: "kb", title: "虚构会话", updatedAt: "2030-01-01T00:00:00Z" };
const answered: HistoryMessage = {
  id: "m1", question: "虚构问题", answer: "中性回答", hidden: false, reason: null, stale: false, citations: [],
  createdAt: "2030-01-01T00:00:00Z", feedback: null, status: "complete", errorCode: null,
};
const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });

describe("question composer", () => {
  it("does not send while an input method is composing, and sends on a plain Enter", async () => {
    const client = clientWith();
    render(<LiveQa client={client} kbId="kb" modelEnabled />);
    const input = screen.getByLabelText("问题输入") as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: "虚构问题" } });

    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(client.raw).not.toHaveBeenCalled();
    expect(input.value).toBe("虚构问题");

    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(client.raw).toHaveBeenCalledTimes(1));
  });
});

describe("answer feedback", () => {
  it("keeps the down-vote form and its input when submitting fails", async () => {
    const client = clientWith({
      conversations: vi.fn(async () => [conversation]),
      messages: vi.fn(async () => [answered]),
      rate: vi.fn(async () => { throw new Error("network"); }),
    });
    render(<LiveQa client={client} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    fireEvent.click(await screen.findByRole("button", { name: /踩/ }));
    fireEvent.change(await screen.findByLabelText("补充说明"), { target: { value: "中性说明" } });
    fireEvent.mouseDown(screen.getByLabelText("点踩原因"));
    fireEvent.click((await screen.findAllByText(/./, { selector: ".ant-select-item-option-content" }))[0]!);
    fireEvent.click(screen.getByRole("button", named("提交")));

    expect(await screen.findByText("反馈提交失败")).toBeTruthy();
    expect(client.rate).toHaveBeenCalledTimes(1);
    expect((screen.getByLabelText("补充说明") as HTMLInputElement).value).toBe("中性说明");
    // 加载图标离场动画期间仍计入按钮名称
    await waitFor(() => expect((screen.getByRole("button", { name: /提\s*交$/ }) as HTMLButtonElement).disabled).toBe(false));
  });
});

describe("conversation switching", () => {
  const second: Conversation = { ...conversation, id: "conv2", title: "另一个会话" };
  const later: HistoryMessage = { ...answered, id: "m2", question: "另一个问题", answer: "另一个回答" };

  it("clears the previous conversation at once and blocks sending until the new one loads", async () => {
    let resolve!: (m: HistoryMessage[]) => void;
    const messages = vi.fn(async (id: string) => id === "conv" ? [answered] : new Promise<HistoryMessage[]>((r) => { resolve = r; }));
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation, second]), messages })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    await screen.findByText("中性回答");

    fireEvent.click(screen.getByText("另一个会话"));
    expect(screen.queryByText("中性回答")).toBeNull();
    expect(screen.queryByText("暂无问答记录")).toBeNull();
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(true);

    resolve([later]);
    expect(await screen.findByText("另一个回答")).toBeTruthy();
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(false);
  });

  it("does not show the previous conversation when loading the next one fails", async () => {
    const messages = vi.fn(async (id: string) => { if (id === "conv") return [answered]; throw new Error("network"); });
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation, second]), messages })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    await screen.findByText("中性回答");
    fireEvent.click(screen.getByText("另一个会话"));
    expect(await screen.findByText("无法读取该会话")).toBeTruthy();
    expect(screen.queryByText("中性回答")).toBeNull();
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(false);
  });

  it("does not stay in the loading state when the knowledge base changes mid-load", async () => {
    const messages = vi.fn(() => new Promise<HistoryMessage[]>(() => {}));
    const client = clientWith({ conversations: vi.fn(async () => [conversation]), messages });
    const { rerender } = render(<LiveQa client={client} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(true);
    rerender(<LiveQa client={client} kbId="kb2" modelEnabled />);
    await waitFor(() => expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(false));
  });

  it("ignores a late history response after starting a new conversation", async () => {
    let resolve!: (m: HistoryMessage[]) => void;
    const messages = vi.fn(() => new Promise<HistoryMessage[]>((r) => { resolve = r; }));
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation]), messages })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    fireEvent.click(screen.getByRole("button", { name: /新会话/ }));
    resolve([answered]);
    expect(await screen.findByText("暂无问答记录")).toBeTruthy();
    expect(screen.queryByText("中性回答")).toBeNull();
  });
});
