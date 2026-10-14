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
