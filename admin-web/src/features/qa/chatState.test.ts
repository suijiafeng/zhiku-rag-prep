import { describe, expect, it } from "vitest";
import { chatReducer, initialChatState, type ChatAction, type ChatState } from "./chatState";

const cite = { documentId: "d1", versionId: "v1", title: "员工手册 v3.2.pdf", excerpt: "…" };
const run = (actions: ChatAction[], from: ChatState = initialChatState) => actions.reduce(chatReducer, from);
const start: ChatAction = { type: "start", question: "年假几天?" };

describe("chat state machine (draft SSE contract)", () => {
  it("completes only after draft, validated, then done", () => {
    const s = run([
      start,
      { type: "event", event: { event: "draft", data: { text: "员工年假" } } },
      { type: "event", event: { event: "draft", data: { text: "按司龄计算[1]" } } },
      { type: "event", event: { event: "validated", data: { citations: [cite], validation: "metadata-only" } } },
      { type: "event", event: { event: "done", data: { status: "complete" } } },
    ]);
    expect(s.status).toBe("complete");
    expect(s.text).toBe("员工年假按司龄计算[1]");
    expect(s.citations).toEqual([cite]);
  });

  it("treats done without validated as a protocol failure and discards the answer", () => {
    const s = run([
      start,
      { type: "event", event: { event: "draft", data: { text: "部分回答" } } },
      { type: "event", event: { event: "done", data: { status: "complete" } } },
    ]);
    expect(s.status).toBe("failed");
    expect(s.text).toBe("");
    expect(s.error?.code).toBe("PROTOCOL");
  });

  it("discards draft and citations on error", () => {
    const s = run([
      start,
      { type: "event", event: { event: "draft", data: { text: "部分" } } },
      { type: "event", event: { event: "validated", data: { citations: [cite], validation: "metadata-only" } } },
      { type: "event", event: { event: "error", data: { code: "FORBIDDEN", message: "无权访问", requestId: "r1" } } },
    ]);
    expect(s).toMatchObject({ status: "failed", text: "", citations: [] });
    expect(s.error).toEqual({ code: "FORBIDDEN", message: "无权访问", requestId: "r1" });
  });

  it("discards the partial answer when the user stops generation", () => {
    const s = run([start, { type: "event", event: { event: "draft", data: { text: "部分" } } }, { type: "cancel" }]);
    expect(s).toMatchObject({ status: "cancelled", text: "", citations: [] });
  });

  it("ignores events after a terminal state", () => {
    const cancelled = run([start, { type: "cancel" }]);
    const after = run([{ type: "event", event: { event: "draft", data: { text: "迟到" } } }], cancelled);
    expect(after).toBe(cancelled);
  });

  it("ignores stream events before a question is started", () => {
    expect(run([{ type: "event", event: { event: "draft", data: { text: "x" } } }])).toBe(initialChatState);
  });

  it("rejects draft text after validated", () => {
    const s = run([
      start,
      { type: "event", event: { event: "validated", data: { citations: [], validation: "metadata-only" } } },
      { type: "event", event: { event: "draft", data: { text: "追加" } } },
    ]);
    expect(s.status).toBe("failed");
    expect(s.text).toBe("");
  });

  it("can start a new question after a terminal state", () => {
    const s = run([start, { type: "cancel" }, { type: "start", question: "新问题" }]);
    expect(s).toMatchObject({ status: "streaming", question: "新问题", text: "" });
  });
});
