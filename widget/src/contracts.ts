/** Provisional future contract; synchronize with the root API spec before implementation. */
export interface Citation {
  documentId: string;
  versionId: string;
  title: string;
  excerpt: string;
}

export type ChatEvent =
  | { event: "draft"; data: { text: string } }
  | { event: "validated"; data: { citations: Citation[]; validation: "metadata-only" } }
  | { event: "done"; data: { status: "complete"; conversationId?: string; messageId?: string } }
  | { event: "error"; data: { code: string; message: string; requestId: string } };

export interface ChatRequest {
  question: string;
  kbId: string;
  /** Continue an own conversation in the same knowledge base. */
  conversationId?: string;
  /** One id per question; generated when omitted. The server accepts it once and never answers a repeat again. */
  clientRequestId?: string;
}

/** Resolve per request in a future adapter. Do not persist or log returned tokens. */
export type AccessTokenGetter = () => Promise<string | undefined>;
