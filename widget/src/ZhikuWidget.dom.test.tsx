// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ZhikuWidget } from "./ZhikuWidget.js";

/** 一个永不结束的 SSE 响应：请求处于在途状态，直到被中止。 */
function pendingStream(signal: AbortSignal | null | undefined) {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode("event: draft\ndata: {\"text\":\"年假\",\"seq\":1}\n\n"));
      signal?.addEventListener("abort", () => controller.error(Object.assign(new Error("aborted"), { name: "AbortError" })), { once: true });
    },
  });
  return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("widget composer", () => {
  it("does not send while an input method is composing, and sends on a plain Enter", async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => pendingStream(init.signal));
    vi.stubGlobal("fetch", fetchMock);
    render(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-a" defaultOpen />);
    const input = screen.getByLabelText("问题") as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: "虚构问题" } });

    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(input.value).toBe("虚构问题");

    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  });
});

describe("widget feedback", () => {
  it("keeps the reason choices open when a down-vote fails, so it can be resent", async () => {
    const sse = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    const answer = [sse("draft", { text: "中性回答", seq: 1 }), sse("validated", { citations: [], validation: "metadata-only" }),
      sse("done", { status: "complete", messageId: "m1" })].join("");
    const fetchMock = vi.fn(async (url: string) => url.endsWith("/feedback")
      ? new Response(null, { status: 503 })
      : new Response(answer, { status: 200, headers: { "Content-Type": "text/event-stream" } }));
    vi.stubGlobal("fetch", fetchMock);
    render(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-a" defaultOpen />);
    fireEvent.change(screen.getByLabelText("问题"), { target: { value: "虚构问题" } });
    fireEvent.click(screen.getByText("发送"));
    fireEvent.click(await screen.findByText("踩"));
    fireEvent.click(screen.getByText("答错了"));

    expect(await screen.findByText("反馈提交失败")).toBeTruthy();
    fireEvent.click(screen.getByText("答错了"));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url.endsWith("/feedback"))).toHaveLength(2));
  });
});

describe("widget context switch", () => {
  it("drops the conversation and aborts the in-flight request when the host switches knowledge base", async () => {
    const calls: { kbId: string; conversationId?: string; signal: AbortSignal | null | undefined }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_url: string, init: RequestInit) => {
      const body = JSON.parse(String(init.body)) as { kbId: string; conversationId?: string };
      calls.push({ kbId: body.kbId, ...(body.conversationId ? { conversationId: body.conversationId } : {}), signal: init.signal });
      return pendingStream(init.signal);
    }));
    const { rerender } = render(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-a" defaultOpen />);

    fireEvent.change(screen.getByLabelText("问题"), { target: { value: "年假有几天" } });
    fireEvent.click(screen.getByText("发送"));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]?.kbId).toBe("kb-a");
    await screen.findByText("年假");
    expect(screen.getByText("年假有几天")).toBeTruthy();

    await act(async () => {
      rerender(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-b" defaultOpen />);
    });
    expect(calls[0]?.signal?.aborted).toBe(true);
    await waitFor(() => expect(screen.queryByText("年假有几天")).toBeNull());
    expect(screen.queryByText("已停止，未完成的回答已丢弃。")).toBeNull();
    expect((screen.getByLabelText("问题") as HTMLTextAreaElement).disabled).toBe(false);

    fireEvent.change(screen.getByLabelText("问题"), { target: { value: "报销流程" } });
    fireEvent.click(screen.getByText("发送"));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]?.kbId).toBe("kb-b");
    expect(calls[1]?.conversationId).toBeUndefined();
    expect(screen.getByText("报销流程")).toBeTruthy();
  });
});
