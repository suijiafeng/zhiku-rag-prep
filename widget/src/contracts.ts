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
  | { event: "done"; data: { status: "complete" } }
  | { event: "error"; data: { code: string; message: string; requestId: string } };

export interface ChatRequest {
  question: string;
  kbId: string;
}

/** Resolve per request in a future adapter. Do not persist or log returned tokens. */
export type AccessTokenGetter = () => Promise<string | undefined>;
