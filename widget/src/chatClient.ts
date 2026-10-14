import type { AccessTokenGetter, ChatEvent, ChatRequest, Citation } from "./contracts.js";
import { createSseParser, type SseFrame } from "./sse.js";

/**
 * 基于 fetch 的草案 SSE 契约客户端（contracts/openapi.yaml，x-sse-draft）。
 * - 每次请求获取短期 token；从不存储或记录；不自动携带 Cookie。
 * - 从不重试：重复 POST 可能造成重复的模型费用。
 * - 只有 `validated` 之后的 `done` 才算完成。错误、取消、协议违规和流提前结束
 *   都会丢弃回答。`onEvent` 草稿是临时的，除非结果为完成，否则必须丢弃。
 */
export interface ChatError {
  code: string;
  message: string;
  requestId?: string;
}

export type ChatOutcome =
  | { status: "complete"; text: string; citations: Citation[]; conversationId?: string; messageId?: string }
  | { status: "failed"; error: ChatError }
  | { status: "cancelled" };

export interface StreamChatOptions {
  /** 可信的后端 URL；绝不是模型提供方 URL。 */
  endpoint: string;
  request: ChatRequest;
  getAccessToken?: AccessTokenGetter;
  signal?: AbortSignal;
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>;
  onEvent?: (event: ChatEvent) => void;
  maxEventChars?: number;
}

const MAX_ERROR_BODY_CHARS = 65_536;

/** v4 UUID；randomUUID 需要安全上下文，getRandomValues 在任何环境都可用。 */
export function newRequestId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6]! & 0x0f) | 0x40;
  b[8] = (b[8]! & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}


class ProtocolError extends Error {}

const failed = (code: string, message: string): ChatOutcome => ({ status: "failed", error: { code, message } });
const protocol = () => failed("PROTOCOL", "响应不符合问答协议");

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === "string";
const isCitation = (v: unknown): v is Citation =>
  isObj(v) && isStr(v.documentId) && isStr(v.versionId) && isStr(v.title) && isStr(v.excerpt);
const isErrorBody = (v: unknown): v is Required<ChatError> =>
  isObj(v) && isStr(v.code) && isStr(v.message) && isStr(v.requestId);

/** 按契约校验单个帧。未知事件名返回 null。 */
function toEvent(frame: SseFrame): ChatEvent | null {
  if (!["draft", "validated", "done", "error"].includes(frame.event)) return null;
  let data: unknown;
  try {
    data = JSON.parse(frame.data);
  } catch {
    throw new ProtocolError();
  }
  if (!isObj(data)) throw new ProtocolError();
  switch (frame.event) {
    case "draft":
      if (isStr(data.text)) return { event: "draft", data: { text: data.text } };
      break;
    case "validated":
      if (data.validation === "metadata-only" && Array.isArray(data.citations) && data.citations.every(isCitation)) {
        return { event: "validated", data: { citations: data.citations.map((c) => ({ ...c })), validation: "metadata-only" } };
      }
      break;
    case "done":
      if (data.status === "complete") {
        return { event: "done", data: {
          status: "complete",
          ...(isStr(data.conversationId) ? { conversationId: data.conversationId } : {}),
          ...(isStr(data.messageId) ? { messageId: data.messageId } : {}),
        } };
      }
      break;
    case "error":
      if (isErrorBody(data)) return { event: "error", data: { code: data.code, message: data.message, requestId: data.requestId } };
      break;
  }
  throw new ProtocolError();
}

/**
 * 错误响应体不可信：最多读取 MAX_ERROR_BODY_CHARS（超出则放弃并取消剩余部分），读取期间
 * 响应取消，并且始终释放响应体。
 */
async function httpError(response: Response, signal?: AbortSignal): Promise<ChatOutcome> {
  const generic = failed(`HTTP_${response.status}`, "请求失败");
  if (!(response.headers.get("Content-Type") ?? "").includes("application/json") || !response.body) {
    response.body?.cancel().catch(() => undefined);
    return generic;
  }
  const reader = response.body.getReader();
  const onAbort = () => {
    reader.cancel().catch(() => undefined);
  };
  signal?.addEventListener("abort", onAbort, { once: true });
  const decoder = new TextDecoder("utf-8");
  let text = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (signal?.aborted) return { status: "cancelled" };
      if (done) break;
      text += decoder.decode(value, { stream: true });
      if (text.length > MAX_ERROR_BODY_CHARS) return generic;
    }
    const body: unknown = JSON.parse(text + decoder.decode());
    if (isErrorBody(body)) return { status: "failed", error: { code: body.code, message: body.message, requestId: body.requestId } };
  } catch {
    if (signal?.aborted) return { status: "cancelled" };
    // 回退为通用错误；响应体不可信
  } finally {
    signal?.removeEventListener("abort", onAbort);
    reader.cancel().catch(() => undefined);
  }
  return generic;
}

const isAbort = (e: unknown, signal?: AbortSignal) => signal?.aborted === true || (e instanceof Error && e.name === "AbortError");

export async function streamChat(options: StreamChatOptions): Promise<ChatOutcome> {
  const { endpoint, request, getAccessToken, signal, onEvent } = options;
  const fetchImpl = options.fetchImpl ?? ((url: string, init: RequestInit) => fetch(url, init));
  // 无效参数在此处抛出，早于获取 token 或发送请求，因此不会遗留未释放的资源。
  const parser = createSseParser(options.maxEventChars);
  if (signal?.aborted) return { status: "cancelled" };

  let token: string | undefined;
  try {
    token = await getAccessToken?.();
  } catch {
    return failed("TOKEN_UNAVAILABLE", "无法获取访问令牌");
  }
  if (signal?.aborted) return { status: "cancelled" };

  const headers = new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" });
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetchImpl(endpoint, {
      method: "POST",
      headers,
      body: JSON.stringify({
        question: request.question,
        kbId: request.kbId,
        ...(request.conversationId ? { conversationId: request.conversationId } : {}),
        clientRequestId: request.clientRequestId ?? newRequestId(),
      }),
      credentials: "omit",
      cache: "no-store",
      ...(signal ? { signal } : {}),
    });
  } catch (e) {
    return isAbort(e, signal) ? { status: "cancelled" } : failed("NETWORK", "网络请求失败");
  }

  if (!response.ok) return httpError(response, signal);
  if (!(response.headers.get("Content-Type") ?? "").startsWith("text/event-stream") || !response.body) {
    response.body?.cancel().catch(() => undefined);
    return protocol();
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let text = "";
  let citations: Citation[] | undefined;

  const handle = (frames: SseFrame[]): ChatOutcome | undefined => {
    for (const frame of frames) {
      const event = toEvent(frame);
      if (!event) continue;
      if (event.event === "draft") {
        if (citations) throw new ProtocolError();
        text += event.data.text;
      } else if (event.event === "validated") {
        if (citations) throw new ProtocolError();
        citations = event.data.citations;
      } else if (event.event === "done" && !citations) {
        throw new ProtocolError();
      }
      onEvent?.(event);
      if (signal?.aborted) return { status: "cancelled" };
      if (event.event === "error") return { status: "failed", error: { ...event.data } };
      if (event.event === "done") {
        return { status: "complete", text, citations: citations ?? [],
          ...(event.data.conversationId ? { conversationId: event.data.conversationId } : {}),
          ...(event.data.messageId ? { messageId: event.data.messageId } : {}) };
      }
    }
    return undefined;
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const outcome = handle(parser.push(decoder.decode(value, { stream: true })));
      if (outcome) return outcome;
    }
    const outcome = handle([...parser.push(decoder.decode()), ...parser.finish()]);
    return outcome ?? failed("STREAM_INCOMPLETE", "回答未完整结束");
  } catch (e) {
    if (isAbort(e, signal)) return { status: "cancelled" };
    if (e instanceof ProtocolError || (e instanceof Error && e.message.includes("SSE"))) return protocol();
    return failed("NETWORK", "连接中断");
  } finally {
    reader.cancel().catch(() => undefined);
  }
}

export type FeedbackReason = "WRONG" | "MISSING" | "BAD_CITATION" | "SHOULD_REFUSE" | "OUTDATED";

export interface RateOptions {
  /** 问答端点；反馈发往同级的 /messages/{id}/feedback 路径。 */
  endpoint: string;
  messageId: string;
  rating: "up" | "down";
  reason?: FeedbackReason;
  getAccessToken?: AccessTokenGetter;
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>;
}

/** 提问者为自己的回答评分。仅尝试一次，不重试，不存储。 */
export async function rateMessage(options: RateOptions): Promise<{ ok: true } | { ok: false; error: ChatError }> {
  const fetchImpl = options.fetchImpl ?? ((url: string, init: RequestInit) => fetch(url, init));
  let token: string | undefined;
  try {
    token = await options.getAccessToken?.();
  } catch {
    return { ok: false, error: { code: "TOKEN_UNAVAILABLE", message: "无法获取访问令牌" } };
  }
  const url = `${options.endpoint.replace(/\/chat\/?$/, "")}/messages/${encodeURIComponent(options.messageId)}/feedback`;
  const headers = new Headers({ "Content-Type": "application/json" });
  if (token) headers.set("Authorization", `Bearer ${token}`);
  try {
    const res = await fetchImpl(url, {
      method: "PUT",
      headers,
      credentials: "omit",
      body: JSON.stringify(options.rating === "down" ? { rating: "down", reason: options.reason } : { rating: "up" }),
    });
    if (res.ok) return { ok: true };
    const failure = await httpError(res);
    return { ok: false, error: failure.status === "failed" ? failure.error : { code: `HTTP_${res.status}`, message: "请求失败" } };
  } catch {
    return { ok: false, error: { code: "NETWORK", message: "网络请求失败" } };
  }
}
