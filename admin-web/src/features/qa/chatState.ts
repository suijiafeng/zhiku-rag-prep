/**
 * contracts/openapi.yaml（x-sse）中 SSE 契约对应的客户端状态。
 * 与 widget 的临时类型保持一致；admin-web 不依赖 widget 包。
 * 规则：done 只在 validated 之后才算数；error、cancel 和协议违规都会丢弃回答。
 */
export interface Citation {
  documentId: string;
  versionId: string;
  title: string;
  excerpt: string;
}

export type ChatEvent =
  | { event: "draft"; data: { text: string } }
  | { event: "validated"; data: { citations: Citation[]; validation: "metadata-only" } }
  | { event: "done"; data: { status: "complete"; conversationId?: string } }
  | { event: "error"; data: { code: string; message: string; requestId: string } };

export type ChatStatus = "idle" | "streaming" | "validated" | "complete" | "failed" | "cancelled";

export interface ChatState {
  status: ChatStatus;
  question: string;
  text: string;
  citations: Citation[];
  error: { code: string; message: string; requestId?: string } | undefined;
}

export type ChatAction = { type: "start"; question: string } | { type: "event"; event: ChatEvent } | { type: "cancel" } | { type: "reset" };

export const initialChatState: ChatState = { status: "idle", question: "", text: "", citations: [], error: undefined };

const terminal = (s: ChatStatus) => s === "complete" || s === "failed" || s === "cancelled";

function discard(state: ChatState, status: "failed" | "cancelled", error?: ChatState["error"]): ChatState {
  return { ...state, status, text: "", citations: [], error };
}

const protocolError = (message: string) => ({ code: "PROTOCOL", message });

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  if (action.type === "reset") return initialChatState;
  if (action.type === "start") {
    if (state.status === "streaming" || state.status === "validated") return state;
    return { ...initialChatState, status: "streaming", question: action.question };
  }
  if (state.status === "idle" || terminal(state.status)) return state;
  if (action.type === "cancel") return discard(state, "cancelled");

  const { event } = action;
  switch (event.event) {
    case "draft":
      return state.status === "streaming"
        ? { ...state, text: state.text + event.data.text }
        : discard(state, "failed", protocolError("validated 之后不应再有 draft"));
    case "validated":
      return state.status === "streaming"
        ? { ...state, status: "validated", citations: event.data.citations }
        : discard(state, "failed", protocolError("重复的 validated"));
    case "done":
      return state.status === "validated"
        ? { ...state, status: "complete" }
        : discard(state, "failed", protocolError("done 之前缺少 validated"));
    case "error":
      return discard(state, "failed", { ...event.data });
  }
}

/** 每个请求分配一个代次；已被取代的请求的事件和回调会被忽略（切换上下文时）。 */
export function createRequestGate() {
  let current = 0;
  return {
    next: () => ++current,
    invalidate: () => {
      current++;
    },
    isCurrent: (generation: number) => generation === current,
  };
}

/** 生成过程中为正在生成的回答的来源；结束后始终为所选历史回答的来源。 */
export function visibleCitations(running: boolean, streamCitations: Citation[], selected: { citations: Citation[] } | undefined) {
  return running ? streamCitations : selected?.citations ?? [];
}
