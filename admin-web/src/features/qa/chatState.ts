/**
 * Client state for the draft SSE contract in contracts/openapi.yaml (x-sse-draft).
 * Mirrors the widget's provisional types; admin-web does not depend on the widget package.
 * Rules: done counts only after validated; error, cancel and protocol violations discard the answer.
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
  | { event: "done"; data: { status: "complete" } }
  | { event: "error"; data: { code: string; message: string; requestId: string } };

export type ChatStatus = "idle" | "streaming" | "validated" | "complete" | "failed" | "cancelled";

export interface ChatState {
  status: ChatStatus;
  question: string;
  text: string;
  citations: Citation[];
  error: { code: string; message: string; requestId?: string } | undefined;
}

export type ChatAction = { type: "start"; question: string } | { type: "event"; event: ChatEvent } | { type: "cancel" };

export const initialChatState: ChatState = { status: "idle", question: "", text: "", citations: [], error: undefined };

const terminal = (s: ChatStatus) => s === "complete" || s === "failed" || s === "cancelled";

function discard(state: ChatState, status: "failed" | "cancelled", error?: ChatState["error"]): ChatState {
  return { ...state, status, text: "", citations: [], error };
}

const protocolError = (message: string) => ({ code: "PROTOCOL", message });

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
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
