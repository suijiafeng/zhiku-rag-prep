import { describe, expect, it, vi } from "vitest";
import { ApiError, createClient } from "./client";
import { streamAnswer } from "./chatStream";
import { chatReducer, initialChatState, type ChatAction } from "../features/qa/chatState";

const enc = new TextEncoder();
const sse = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const stream = (text: string) =>
  new Response(new ReadableStream({ start(c) { c.enqueue(enc.encode(text)); c.close(); } }), {
    headers: { "Content-Type": "text/event-stream" },
  });

describe("api client", () => {
  it("sends the in-memory token and parses JSON", async () => {
    const fetchImpl = vi.fn(async (_u: string, _i: RequestInit) => json([{ id: "kb1", name: "制度库" }]));
    const client = createClient("tok", fetchImpl);
    expect(await client.knowledgeBases()).toEqual([{ id: "kb1", name: "制度库" }]);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe("/api/knowledge-bases");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer tok");
    expect(init.credentials).toBe("omit");
  });

  it("maps contract errors and keeps the request id", async () => {
    const client = createClient("tok", async () => json({ code: "FORBIDDEN", message: "Access denied", requestId: "r1" }, 403));
    await expect(client.documents("kb1")).rejects.toMatchObject({ status: 403, code: "FORBIDDEN", requestId: "r1" });
  });

  it("does not trust non-contract error bodies", async () => {
    const client = createClient("tok", async () => new Response("<html>", { status: 502 }));
    const err = await client.status().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ code: "HTTP_502" });
  });

  it("encodes path segments", async () => {
    const fetchImpl = vi.fn(async (_u: string, _i: RequestInit) => json([]));
    await createClient("tok", fetchImpl).documents("a/b");
    expect(fetchImpl.mock.calls[0]![0]).toBe("/api/knowledge-bases/a%2Fb/documents");
  });

  it("deletes a document with DELETE and accepts an empty 202", async () => {
    const fetchImpl = vi.fn(async (_u: string, _i: RequestInit) => new Response(null, { status: 202 }));
    expect(await createClient("tok", fetchImpl).deleteDocument("kb", "d/1")).toBeUndefined();
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe("/api/knowledge-bases/kb/documents/d%2F1");
    expect(init.method).toBe("DELETE");
  });

  it("uploads multipart content with PUT", async () => {
    const fetchImpl = vi.fn(async (_u: string, _i: RequestInit) => json({ jobId: "j1" }, 202));
    const file = new File(["# 手册"], "handbook.md");
    expect(await createClient("tok", fetchImpl).upload("kb", "doc", "v", file)).toEqual({ jobId: "j1" });
    const init = fetchImpl.mock.calls[0]![1];
    expect(init.method).toBe("PUT");
    expect(init.body).toBeInstanceOf(FormData);
  });
});

describe("streamAnswer", () => {
  const run = async (response: Response) => {
    const actions: ChatAction[] = [{ type: "start", question: "年假?" }];
    let conversation: string | undefined;
    await streamAnswer(createClient("tok", async () => response), { kbId: "kb", question: "年假?" }, (a) => actions.push(a), {
      onConversation: (id) => (conversation = id),
    });
    return { state: actions.reduce(chatReducer, initialChatState), conversation };
  };

  it("drives the reducer to complete and reports the conversation", async () => {
    const citation = { documentId: "d", versionId: "v", title: "手册", excerpt: "…" };
    const { state, conversation } = await run(stream(
      sse("draft", { text: "年假[1]" }) + sse("validated", { citations: [citation], validation: "metadata-only" }) +
      sse("done", { status: "complete", conversationId: "c1" })));
    expect(state).toMatchObject({ status: "complete", text: "年假[1]", citations: [citation] });
    expect(conversation).toBe("c1");
  });

  it("posts each question with its own request id", async () => {
    const bodies: unknown[] = [];
    const client = createClient("tok", async (_u, init) => {
      bodies.push(JSON.parse(String(init?.body)));
      return json({ code: "FORBIDDEN", message: "x" }, 403);
    });
    await streamAnswer(client, { kbId: "kb", question: "年假?" }, () => {});
    await streamAnswer(client, { kbId: "kb", question: "年假?" }, () => {});
    const ids = bodies.map((b) => (b as { clientRequestId: string }).clientRequestId);
    expect(ids[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(ids[0]).not.toBe(ids[1]);
  });

  it("turns HTTP errors into a failed state", async () => {
    const { state } = await run(json({ code: "DEPENDENCY_UNAVAILABLE", message: "x", requestId: "r" }, 503));
    expect(state).toMatchObject({ status: "failed", error: { code: "DEPENDENCY_UNAVAILABLE" } });
  });

  it("fails when the stream ends early or carries malformed JSON", async () => {
    expect((await run(stream(sse("draft", { text: "半" })))).state.status).toBe("failed");
    expect((await run(stream("event: draft\ndata: {oops}\n\n"))).state).toMatchObject({ status: "failed", error: { code: "PROTOCOL" } });
  });
});
