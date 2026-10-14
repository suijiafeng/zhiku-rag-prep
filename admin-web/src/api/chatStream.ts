import type { ChatAction, ChatEvent, Citation } from "../features/qa/chatState";
import { toApiError, type ApiClient } from "./client";

/**
 * 将 POST /api/chat 的结果送入 chat reducer。帧在此处解析并做结构检查；reducer 负责保证
 * 顺序（done 只在 validated 之后）。提前结束、格式错误的帧和 HTTP 错误都转为 error 事件。
 * 从不重试。
 */
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === "string";
const isCitation = (v: unknown): v is Citation =>
  isObj(v) && isStr(v.documentId) && isStr(v.versionId) && isStr(v.title) && isStr(v.excerpt);

function toEvent(name: string, raw: string): ChatEvent | null {
  if (!["draft", "validated", "done", "error"].includes(name)) return null;
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return protocol();
  }
  if (!isObj(data)) return protocol();
  if (name === "draft" && isStr(data.text)) {
    return { event: "draft", data: typeof data.seq === "number" ? { text: data.text, seq: data.seq } : { text: data.text } };
  }
  if (name === "validated" && data.validation === "metadata-only" && Array.isArray(data.citations) && data.citations.every(isCitation)) {
    return { event: "validated", data: { citations: data.citations, validation: "metadata-only" } };
  }
  if (name === "done" && data.status === "complete") {
    return { event: "done", data: isStr(data.conversationId) ? { status: "complete", conversationId: data.conversationId } : { status: "complete" } };
  }
  if (name === "error" && isStr(data.code) && isStr(data.message)) {
    return { event: "error", data: {
      code: data.code, message: data.message, requestId: isStr(data.requestId) ? data.requestId : "",
      ...(isStr(data.traceId) ? { traceId: data.traceId } : {}),
      ...(typeof data.retryable === "boolean" ? { retryable: data.retryable } : {}),
    } };
  }
  return protocol();
}

/** v4 UUID；randomUUID 需要安全上下文，getRandomValues 在任何环境都可用。 */
export function newRequestId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6]! & 0x0f) | 0x40;
  b[8] = (b[8]! & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const fail = (code: string, message: string): ChatEvent => ({ event: "error", data: { code, message, requestId: "" } });
const protocol = () => fail("PROTOCOL", "响应不符合问答协议");

export async function streamAnswer(
  client: ApiClient,
  request: { kbId: string; question: string; conversationId?: string },
  dispatch: (action: ChatAction) => void,
  options: { signal?: AbortSignal; onConversation?: (id: string) => void } = {},
): Promise<void> {
  const { signal, onConversation } = options;
  const emit = (event: ChatEvent) => dispatch({ type: "event", event });
  const clientRequestId = newRequestId();
  let res: Response;
  try {
    res = await client.raw("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ ...request, clientRequestId }),
      ...(signal ? { signal } : {}),
    });
  } catch {
    if (signal?.aborted) dispatch({ type: "cancel" });
    else emit(fail("NETWORK", "网络请求失败"));
    return;
  }
  if (!res.ok) {
    const err = await toApiError(res);
    emit({ event: "error", data: { code: err.code, message: err.message, requestId: err.requestId ?? "" } });
    return;
  }
  if (!(res.headers.get("Content-Type") ?? "").startsWith("text/event-stream") || !res.body) {
    emit(protocol());
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let terminal = false;
  let validated = false;
  let broken: ChatEvent | undefined;
  const handle = (block: string) => {
    let name = "message";
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) name = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (data.length === 0) return;
    const event = toEvent(name, data.join("\n"));
    if (!event) return;
    emit(event);
    if (event.event === "validated") validated = true;
    // 与 reducer 规则一致：done 只在 validated 之后才算数，因此被拒绝的流不产生副作用。
    if (event.event === "done" && validated && event.data.conversationId) onConversation?.(event.data.conversationId);
    if (event.event === "done" || event.event === "error") terminal = true;
  };
  try {
    while (!terminal) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      if (buffer.length > 1_048_576) {
        emit(protocol());
        return;
      }
      let cut: RegExpExecArray | null;
      while (!terminal && (cut = /\r?\n\r?\n/.exec(buffer))) {
        handle(buffer.slice(0, cut.index));
        buffer = buffer.slice(cut.index + cut[0].length);
      }
    }
    if (!terminal) broken = fail("STREAM_INCOMPLETE", "回答未完整结束");
  } catch {
    if (signal?.aborted) dispatch({ type: "cancel" });
    else broken = fail("NETWORK", "连接中断");
  } finally {
    reader.cancel().catch(() => undefined);
  }
  if (broken) emit(await reconcile(client, clientRequestId, broken, onConversation));
}

/** 流在结束事件前中断时，以服务端结果为准：回答已保存就从历史载入，不重新提问。 */
async function reconcile(client: ApiClient, clientRequestId: string, broken: ChatEvent, onConversation?: (id: string) => void): Promise<ChatEvent> {
  try {
    const outcome = await client.chatRequest(clientRequestId);
    if (outcome.status === "complete" && outcome.conversationId) {
      onConversation?.(outcome.conversationId);
      return fail("ANSWER_SAVED", "连接中断，但回答已在服务端完成，已从历史记录载入");
    }
  } catch {
    // 查询失败时保留原错误
  }
  return broken;
}
