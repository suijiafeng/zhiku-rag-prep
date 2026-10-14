/**
 * Adapter for the server API (contracts/openapi.yaml). The bearer token lives only in memory for the
 * page session; nothing is written to localStorage, sessionStorage or cookies.
 */
export type Fetch = (url: string, init: RequestInit) => Promise<Response>;

export interface Status {
  name: string;
  stage: string;
  prdVersion: string;
  modelEnabled: boolean;
  chatModel: string | null;
  embeddingModel: string | null;
  retrieval: string;
  businessTimeZone: string;
  maxUploadBytes: number;
  uploadFormats: string[];
}

export interface KnowledgeBase {
  id: string;
  name: string;
}

export type IndexState = "pending" | "processing" | "ready" | "failed";

export interface VersionInfo {
  id: string;
  versionLabel: string;
  effectiveFrom: string;
  effectiveUntil: string | null;
  approved: boolean;
  published: boolean;
  withdrawn: boolean;
  superseded: boolean;
  indexStatus: IndexState;
  indexError: string | null;
}

export interface DocumentInfo {
  id: string;
  title: string;
  createdAt: string;
  versions: VersionInfo[];
}

export interface TraceResult {
  traceId: string;
  question: string;
  at: string;
  embeddingModel: string;
  dimension: number;
  stages: { key: string; name: string; status: "success" | "failed" | "skipped" | "cancelled"; elapsedMs: number | null; summary: string }[];
  candidates: {
    chunkId: string; documentId: string; title: string; versionId: string; versionLabel: string; seq: number;
    heading: string | null; startOffset: number; endOffset: number; text: string; score: number; rank: number;
  }[];
}

export interface Conversation {
  id: string;
  kbId: string;
  title: string;
  updatedAt: string;
}

export interface HistoryCitation {
  documentId: string;
  versionId: string;
  title: string;
  excerpt: string;
}

export type FeedbackReason = "WRONG" | "MISSING" | "BAD_CITATION" | "SHOULD_REFUSE" | "OUTDATED";
export type FeedbackStatus = "open" | "in_progress" | "to_verify" | "resolved" | "wont_fix";

export interface FeedbackInfo {
  rating: "up" | "down";
  reason: FeedbackReason | null;
  comment: string | null;
  version: number;
  status: FeedbackStatus;
  updatedAt: string;
}

export interface RecordItem {
  messageId: string;
  createdAt: string;
  own: boolean;
  question: string | null;
  citationCount: number;
  rating: "up" | "down" | null;
  reason: FeedbackReason | null;
  comment: string | null;
  status: FeedbackStatus | null;
  rootCause: string | null;
}

export interface RecordStats {
  total: number;
  up: number;
  down: number;
  unrated: number;
  reasons: Partial<Record<FeedbackReason, number>>;
}

export interface HistoryMessage {
  id: string;
  question: string;
  answer: string | null;
  hidden: boolean;
  reason: string | null;
  stale: boolean;
  citations: HistoryCitation[];
  createdAt: string;
  feedback: FeedbackInfo | null;
}

export type Role = "IT_ADMIN" | "KB_OPERATOR" | "EMPLOYEE";
export type KbAction = "ASK_AND_CITE" | "MANAGE_DOCUMENTS" | "DEBUG_AND_EVALUATE" | "READ_OTHERS_CONVERSATIONS";
export type Rule = "ALLOWED" | "REQUIRES_EXPLICIT_GRANT" | "NEVER";

export interface RolePolicy {
  roles: Role[];
  actions: KbAction[];
  rules: Record<Role, Record<KbAction, Rule>>;
}

export interface Me {
  userId: string;
  enterpriseId: string;
  roles: Role[];
  knowledgeBaseIds: string[];
}

export interface AdminKnowledgeBase {
  id: string;
  name: string;
  memberCount: number;
  createdAt: string;
}

export interface Grant {
  userId: string;
  grantedAt: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | undefined;

  constructor(status: number, code: string, message: string, requestId?: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

const isStr = (v: unknown): v is string => typeof v === "string";

/** Uses the body only when it matches the contract error shape; anything else is untrusted. */
export async function toApiError(res: Response): Promise<ApiError> {
  try {
    const body: unknown = JSON.parse((await res.text()).slice(0, 65_536));
    if (typeof body === "object" && body !== null) {
      const { code, message, requestId } = body as Record<string, unknown>;
      if (isStr(code) && isStr(message)) return new ApiError(res.status, code, message, isStr(requestId) ? requestId : undefined);
    }
  } catch {
    // fall through
  }
  return new ApiError(res.status, `HTTP_${res.status}`, "请求失败");
}

export function createClient(token: string | undefined, fetchImpl: Fetch = (u, i) => fetch(u, i)) {
  const raw = (path: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    return fetchImpl(path, { ...init, headers, credentials: "omit", cache: "no-store" });
  };

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await raw(path, init);
    if (!res.ok) throw await toApiError(res);
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  const e = encodeURIComponent;
  const kb = (id: string) => `/api/knowledge-bases/${e(id)}`;
  const version = (kbId: string, doc: string, v: string) => `${kb(kbId)}/documents/${e(doc)}/versions/${e(v)}`;
  const post = (body: unknown): RequestInit => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

  return {
    raw,
    status: () => request<Status>("/api/status"),
    knowledgeBases: () => request<KnowledgeBase[]>("/api/knowledge-bases"),
    documents: (kbId: string) => request<DocumentInfo[]>(`${kb(kbId)}/documents`),
    createDocument: (kbId: string, title: string) => request<{ id: string }>(`${kb(kbId)}/documents`, post({ title })),
    createVersion: (kbId: string, doc: string, body: { versionLabel: string; effectiveFrom: string; effectiveUntil?: string }) =>
      request<{ id: string }>(`${kb(kbId)}/documents/${e(doc)}/versions`, post(body)),
    transition: (kbId: string, doc: string, v: string, action: "approve" | "publish" | "withdraw") =>
      request<void>(`${version(kbId, doc, v)}/${action}`, { method: "POST" }),
    upload: (kbId: string, doc: string, v: string, file: File) => {
      const form = new FormData();
      form.append("file", file);
      return request<{ jobId: string }>(`${version(kbId, doc, v)}/content`, { method: "PUT", body: form });
    },
    reindex: (kbId: string, doc: string, v: string) => request<void>(`${version(kbId, doc, v)}/reindex`, { method: "POST" }),
    retrievalDebug: (kbId: string, query: string, k?: number) =>
      request<TraceResult>(`${kb(kbId)}/retrieval-debug`, post(k === undefined ? { query } : { query, k })),
    me: () => request<Me>("/api/me"),
    rolePolicy: () => request<RolePolicy>("/api/policy/roles"),
    adminKnowledgeBases: () => request<AdminKnowledgeBase[]>("/api/admin/knowledge-bases"),
    createKnowledgeBase: (name: string) => request<KnowledgeBase>("/api/knowledge-bases", post({ name })),
    grants: (kbId: string) => request<Grant[]>(`${kb(kbId)}/grants`),
    grant: (kbId: string, userId: string) => request<void>(`${kb(kbId)}/grants/${e(userId)}`, { method: "PUT" }),
    revoke: (kbId: string, userId: string) => request<void>(`${kb(kbId)}/grants/${e(userId)}`, { method: "DELETE" }),
    rate: (messageId: string, body: { rating: "up" | "down"; reason?: FeedbackReason; comment?: string }) =>
      request<FeedbackInfo>(`/api/messages/${e(messageId)}/feedback`, { ...post(body), method: "PUT" }),
    records: (kbId: string, filter: "all" | "up" | "down" | "none") =>
      request<{ items: RecordItem[]; stats: RecordStats; canReadOthers: boolean }>(`${kb(kbId)}/records?filter=${filter}`),
    updateFeedback: (kbId: string, messageId: string, body: { status: FeedbackStatus; rootCause?: string }) =>
      request<void>(`${kb(kbId)}/feedback/${e(messageId)}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
    conversations: () => request<Conversation[]>("/api/conversations"),
    messages: (id: string) => request<HistoryMessage[]>(`/api/conversations/${e(id)}/messages`),
  };
}

export type ApiClient = ReturnType<typeof createClient>;
