import { describe, expect, it, vi } from "vitest";
import { rateMessage, streamChat } from "./chatClient.js";

const enc = new TextEncoder();
const sse = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const citation = { documentId: "d1", versionId: "v1", title: "员工手册", excerpt: "年假…" };
const happy = [
  sse("draft", { text: "年假" }),
  sse("draft", { text: "按司龄计算[1]" }),
  sse("validated", { citations: [citation], validation: "metadata-only" }),
  sse("done", { status: "complete" }),
];

function streamResponse(parts: (string | Uint8Array)[], init: ResponseInit = {}) {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const p of parts) controller.enqueue(typeof p === "string" ? enc.encode(p) : p);
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream; charset=utf-8" }, ...init });
}

const base = { endpoint: "https://kb.example.test/api/chat", request: { question: "年假几天?", kbId: "kb-1" } };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("streamChat", () => {
  it("completes after draft, validated and done, reassembling split UTF-8", async () => {
    const bytes = enc.encode(happy.join(""));
    const cut = bytes.indexOf(0xe5) + 1; // split inside the first multi-byte character
    const fetchImpl = vi.fn(async () => streamResponse([bytes.slice(0, cut), bytes.slice(cut)]));
    const drafts: string[] = [];
    const out = await streamChat({ ...base, fetchImpl, onEvent: (e) => e.event === "draft" && drafts.push(e.data.text) });
    expect(out).toEqual({ status: "complete", text: "年假按司龄计算[1]", citations: [citation] });
    expect(drafts).toEqual(["年假", "按司龄计算[1]"]);
  });

  it("posts JSON with a per-request bearer token and no ambient credentials", async () => {
    const fetchImpl = vi.fn(async (_url: string, _init: RequestInit) => streamResponse(happy));
    await streamChat({ ...base, fetchImpl, getAccessToken: async () => "short-lived" });
    const [url, init] = fetchImpl.mock.calls[0]!;
    const headers = new Headers(init.headers);
    expect(url).toBe(base.endpoint);
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("omit");
    expect(headers.get("Authorization")).toBe("Bearer short-lived");
    expect(headers.get("Accept")).toBe("text/event-stream");
    expect(JSON.parse(String(init.body))).toEqual({ question: "年假几天?", kbId: "kb-1", clientRequestId: expect.stringMatching(UUID) });
  });

  it("sends a new request id per question, or the one supplied, so the server never answers a repeat twice", async () => {
    const fetchImpl = vi.fn(async (_url: string, _init: RequestInit) => streamResponse(happy));
    await streamChat({ ...base, fetchImpl });
    await streamChat({ ...base, fetchImpl });
    await streamChat({ ...base, request: { ...base.request, clientRequestId: "11111111-1111-4111-8111-111111111111" }, fetchImpl });
    const ids = fetchImpl.mock.calls.map(([, init]) => JSON.parse(String(init.body)).clientRequestId);
    expect(ids[0]).not.toBe(ids[1]);
    expect(ids[2]).toBe("11111111-1111-4111-8111-111111111111");
  });

  it("reads at most the error-body limit and cancels the rest", async () => {
    let pulled = 0;
    let cancelled = false;
    const chunk = enc.encode("x".repeat(16_384));
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (pulled >= 12) return controller.close();
        pulled++;
        controller.enqueue(chunk);
      },
      cancel() {
        cancelled = true;
      },
    }, { highWaterMark: 0 });
    const fetchImpl = vi.fn(async () => new Response(body, { status: 500, headers: { "Content-Type": "application/json" } }));
    const outcome = await streamChat({ ...base, fetchImpl });
    expect(outcome).toEqual({ status: "failed", error: { code: "HTTP_500", message: "请求失败" } });
    expect(pulled * chunk.length).toBeLessThanOrEqual(65_536 + chunk.length);
    expect(cancelled).toBe(true);
  });

  it("reports cancellation while an error body is still being read", async () => {
    const controller = new AbortController();
    const body = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(enc.encode("{\"code\":")); } });
    const fetchImpl = vi.fn(async () => {
      setTimeout(() => controller.abort(), 20);
      return new Response(body, { status: 401, headers: { "Content-Type": "application/json" } });
    });
    expect(await streamChat({ ...base, fetchImpl, signal: controller.signal })).toEqual({ status: "cancelled" });
  });

  it("rejects an invalid event limit before sending anything", async () => {
    const fetchImpl = vi.fn(async (_url: string, _init: RequestInit) => streamResponse(happy));
    await expect(streamChat({ ...base, fetchImpl, maxEventChars: 0 })).rejects.toThrow("maxEventChars");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("omits Authorization when no token is provided", async () => {
    const fetchImpl = vi.fn(async (_url: string, _init: RequestInit) => streamResponse(happy));
    await streamChat({ ...base, fetchImpl, getAccessToken: async () => undefined });
    expect(new Headers(fetchImpl.mock.calls[0]![1].headers).has("Authorization")).toBe(false);
  });

  it("fails without calling fetch when the token getter throws", async () => {
    const fetchImpl = vi.fn();
    const out = await streamChat({ ...base, fetchImpl, getAccessToken: async () => { throw new Error("x"); } });
    expect(out).toMatchObject({ status: "failed", error: { code: "TOKEN_UNAVAILABLE" } });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("treats done without validated as a protocol failure", async () => {
    const fetchImpl = async () => streamResponse([sse("draft", { text: "部分" }), sse("done", { status: "complete" })]);
    expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "failed", error: { code: "PROTOCOL" } });
  });

  it("surfaces a server error event and discards the draft", async () => {
    const err = { code: "FORBIDDEN", message: "无权访问", requestId: "r-1" };
    const fetchImpl = async () => streamResponse([sse("draft", { text: "部分" }), sse("error", err)]);
    const out = await streamChat({ ...base, fetchImpl });
    expect(out).toEqual({ status: "failed", error: err });
  });

  it("maps a JSON HTTP error body and keeps its request id", async () => {
    const body = JSON.stringify({ code: "UNAUTHENTICATED", message: "Authentication required", requestId: "r-2" });
    const fetchImpl = async () => new Response(body, { status: 401, headers: { "Content-Type": "application/json" } });
    expect(await streamChat({ ...base, fetchImpl })).toEqual({
      status: "failed",
      error: { code: "UNAUTHENTICATED", message: "Authentication required", requestId: "r-2" },
    });
  });

  it("does not trust a malformed HTTP error body", async () => {
    const fetchImpl = async () => new Response("<html>oops</html>", { status: 503, headers: { "Content-Type": "text/html" } });
    expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "failed", error: { code: "HTTP_503" } });
  });

  it("rejects a successful response that is not an event stream", async () => {
    const fetchImpl = async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "failed", error: { code: "PROTOCOL" } });
  });

  it("fails when the stream ends before done", async () => {
    const fetchImpl = async () => streamResponse(happy.slice(0, 3));
    expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "failed", error: { code: "STREAM_INCOMPLETE" } });
  });

  it("fails on malformed JSON or payloads that break the contract", async () => {
    const bad = [
      "event: draft\ndata: {not json}\n\n",
      sse("draft", { text: 1 }),
      sse("validated", { citations: [citation], validation: "semantic" }),
      sse("validated", { citations: [{ ...citation, title: 3 }], validation: "metadata-only" }),
      sse("done", { status: "partial" }),
    ];
    for (const frame of bad) {
      const fetchImpl = async () => streamResponse([frame, ...happy]);
      expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "failed", error: { code: "PROTOCOL" } });
    }
  });

  it("ignores unknown event names", async () => {
    const fetchImpl = async () => streamResponse([sse("heartbeat", {}), ...happy]);
    expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "complete" });
  });

  it("fails on an oversized event instead of buffering it", async () => {
    const fetchImpl = async () => streamResponse([sse("draft", { text: "x".repeat(200) })]);
    expect(await streamChat({ ...base, fetchImpl, maxEventChars: 64 })).toMatchObject({ status: "failed", error: { code: "PROTOCOL" } });
  });

  it("returns cancelled and never retries when aborted mid-stream", async () => {
    const controller = new AbortController();
    const fetchImpl = vi.fn(async (_url: string, init: RequestInit) => {
      const body = new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(enc.encode(sse("draft", { text: "部分" })));
          init.signal?.addEventListener("abort", () => c.error(new DOMException("aborted", "AbortError")));
        },
      });
      return new Response(body, { headers: { "Content-Type": "text/event-stream" } });
    });
    const run = streamChat({ ...base, fetchImpl, signal: controller.signal, onEvent: () => controller.abort() });
    expect(await run).toEqual({ status: "cancelled" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("returns cancelled when already aborted before sending", async () => {
    const controller = new AbortController();
    controller.abort();
    const fetchImpl = vi.fn();
    expect(await streamChat({ ...base, fetchImpl, signal: controller.signal })).toEqual({ status: "cancelled" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("reports network failures without retrying", async () => {
    const fetchImpl = vi.fn(async () => { throw new TypeError("network"); });
    expect(await streamChat({ ...base, fetchImpl })).toMatchObject({ status: "failed", error: { code: "NETWORK" } });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

describe("conversation and feedback", () => {
  it("passes conversation and message ids from done and sends conversationId when continuing", async () => {
    const fetchImpl = vi.fn(async (_url: string, _init: RequestInit) => streamResponse([
      ...happy.slice(0, 3),
      sse("done", { status: "complete", conversationId: "c1", messageId: "m1" }),
    ]));
    const out = await streamChat({ ...base, request: { ...base.request, conversationId: "c0" }, fetchImpl });
    expect(out).toMatchObject({ status: "complete", conversationId: "c1", messageId: "m1" });
    expect(JSON.parse(String(fetchImpl.mock.calls[0]![1].body))).toMatchObject({ conversationId: "c0" });
  });

  it("rates a message next to the chat endpoint with a fresh token", async () => {
    const fetchImpl = vi.fn(async (_url: string, _init: RequestInit) =>
      new Response(JSON.stringify({ rating: "down" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    const out = await rateMessage({ endpoint: "https://kb.example.test/api/chat", messageId: "m/1", rating: "down", reason: "MISSING",
      getAccessToken: async () => "t", fetchImpl });
    expect(out).toEqual({ ok: true });
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe("https://kb.example.test/api/messages/m%2F1/feedback");
    expect(init.method).toBe("PUT");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer t");
    expect(JSON.parse(String(init.body))).toEqual({ rating: "down", reason: "MISSING" });
  });

  it("reports rating failures without retrying", async () => {
    const fetchImpl = vi.fn(async () => new Response("{}", { status: 403, headers: { "Content-Type": "application/json" } }));
    expect(await rateMessage({ endpoint: "/api/chat", messageId: "m", rating: "up", fetchImpl })).toMatchObject({ ok: false });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});
