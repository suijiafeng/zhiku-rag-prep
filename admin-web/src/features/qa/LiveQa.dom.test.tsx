// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient } from "../../api/client";
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
