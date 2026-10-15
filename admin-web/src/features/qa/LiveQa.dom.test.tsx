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
