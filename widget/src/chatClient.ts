import type { AccessTokenGetter, ChatEvent, ChatRequest, Citation } from "./contracts.js";
import { createSseParser, type SseFrame } from "./sse.js";

/**
 * fetch-based client for the draft SSE contract (contracts/openapi.yaml, x-sse-draft).
 * - Fetches a short-lived token per request; never stores or logs it; sends no ambient cookies.
 * - Never retries: a repeated POST could duplicate model cost.
 * - Only `done` after `validated` completes. Error, cancel, protocol violations and early stream end
 *   all discard the answer. `onEvent` drafts are provisional and must be dropped unless the outcome is complete.
 */
export interface ChatError {
  code: string;
  message: string;
  requestId?: string;
}

export type ChatOutcome =
  | { status: "complete"; text: string; citations: Citation[] }
  | { status: "failed"; error: ChatError }
  | { status: "cancelled" };

export interface StreamChatOptions {
  /** Trusted backend URL; never a model provider URL. */
  endpoint: string;
  request: ChatRequest;
  getAccessToken?: AccessTokenGetter;
  signal?: AbortSignal;
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>;
  onEvent?: (event: ChatEvent) => void;
  maxEventChars?: number;
}

const MAX_ERROR_BODY_CHARS = 65_536;

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

/** Validates one frame against the contract. Returns null for unknown event names. */
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
      if (data.status === "complete") return { event: "done", data: { status: "complete" } };
      break;
    case "error":
      if (isErrorBody(data)) return { event: "error", data: { code: data.code, message: data.message, requestId: data.requestId } };
      break;
  }
  throw new ProtocolError();
}

async function httpError(response: Response): Promise<ChatOutcome> {
  if ((response.headers.get("Content-Type") ?? "").includes("application/json")) {
    try {
      const body: unknown = JSON.parse((await response.text()).slice(0, MAX_ERROR_BODY_CHARS));
      if (isErrorBody(body)) return { status: "failed", error: { code: body.code, message: body.message, requestId: body.requestId } };
    } catch {
      // fall through to a generic error; the body is untrusted
    }
  }
  return failed(`HTTP_${response.status}`, "请求失败");
}

const isAbort = (e: unknown, signal?: AbortSignal) => signal?.aborted === true || (e instanceof Error && e.name === "AbortError");

export async function streamChat(options: StreamChatOptions): Promise<ChatOutcome> {
  const { endpoint, request, getAccessToken, signal, onEvent } = options;
  const fetchImpl = options.fetchImpl ?? ((url: string, init: RequestInit) => fetch(url, init));
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
      body: JSON.stringify({ question: request.question, kbId: request.kbId }),
      credentials: "omit",
      cache: "no-store",
      ...(signal ? { signal } : {}),
    });
  } catch (e) {
    return isAbort(e, signal) ? { status: "cancelled" } : failed("NETWORK", "网络请求失败");
  }

  if (!response.ok) return httpError(response);
  if (!(response.headers.get("Content-Type") ?? "").startsWith("text/event-stream") || !response.body) return protocol();

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  const parser = createSseParser(options.maxEventChars);
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
      if (event.event === "done") return { status: "complete", text, citations: citations ?? [] };
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
