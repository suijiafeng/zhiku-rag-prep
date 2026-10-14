/**
 * 服务端 API 适配层（contracts/openapi.yaml）。bearer token 只在页面会话期间保存在内存中；
 * 不写入 localStorage、sessionStorage 或 cookie。
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

/** 仅对已删除文档存在：访问已停止；status 表示后台内容清理状态。 */
export interface DeletionInfo {
  status: "pending" | "running" | "done" | "failed";
  deletedAt: string;
  error: string | null;
}

export interface DocumentInfo {
  id: string;
  title: string;
  createdAt: string;
  versions: VersionInfo[];
  deletion?: DeletionInfo | null;
}

export interface TraceStage {
  key: string;
  name: string;
  status: "success" | "failed" | "skipped" | "cancelled";
  startedAt?: string | null;
  endedAt?: string | null;
  elapsedMs: number | null;
  summary: string;
}

/** 服务端存档的轨迹：只有元数据，没有问题和片段正文。 */
export interface StoredTrace {
  traceId: string;
  origin: "chat" | "debug" | "evaluation";
  at: string;
  k: number;
  embeddingModel: string | null;
  dimension: number | null;
  messageId: string | null;
  createdAt: string;
  stages: TraceStage[];
  candidates: { rank: number; chunkId: string; versionId: string; documentTitle: string | null; score: number }[];
}

export interface TraceResult {
  traceId: string;
  question: string;
  at: string;
  embeddingModel: string;
  dimension: number;
  stages: TraceStage[];
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

export type TurnOutcome = "complete" | "refused" | "cancelled" | "failed";

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
  resolution: string | null;
  verificationCaseId: string | null;
  outcome: TurnOutcome;
  /** 回答（或其所依据的更早轮次）的某个来源不可用后为 false；非回答为 null。 */
  sourceAvailable: boolean | null;
  traceId?: string | null;
}

export interface HandlingEntry {
  status: FeedbackStatus;
  rootCause: string | null;
  resolution: string | null;
  verificationCaseId: string | null;
  changedBy: string;
  changedAt: string;
  feedbackVersion: number | null;
}

export interface FeedbackUpdate {
  status: FeedbackStatus;
  rootCause?: string;
  resolution?: string;
  verificationCaseId?: string;
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
  status: TurnOutcome;
  errorCode: string | null;
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

export interface OverviewApi {
  documents: number;
  versions: number;
  readyVersions: number;
  chunks: number;
  vectors: number;
  questionsToday: number;
  questionsYesterday: number;
  feedback: { up: number; down: number };
  daily: { day: string; count: number }[];
  embeddingSpaces: { model: string; dimension: number; vectors: number }[];
  lastIndexedAt: string | null;
  recentDocuments: { documentId: string; title: string; versionLabel: string; indexStatus: IndexState; chunks: number; createdAt: string }[];
  recentQuestions: { messageId: string; own: boolean; question: string | null; citationCount: number; createdAt: string }[];
}

export interface EvidenceInput {
  versionId: string;
  quote: string;
  /** 从 1 开始；引文在该版本中出现不止一次时需要。 */
  occurrence?: number;
}

export interface EvalEvidence {
  versionId: string;
  documentTitle: string;
  versionLabel: string;
  startOffset: number;
  endOffset: number;
  /** 来源版本被撤回或其文档被删除后为 null。 */
  quote: string | null;
  available: boolean;
}

export interface EvalCase {
  id: string;
  question: string;
  label: "answerable" | "clarify" | "handoff";
  status: "candidate" | "approved";
  source: string;
  createdAt: string;
  evidence: EvalEvidence[];
  /** 生成该用例的反馈消息；只有此类用例才能验证该反馈的修复。 */
  originMessageId: string | null;
  expectedAnswer: string | null;
  requiredFacts: string[];
  appliesTo: string | null;
  /** 按此时点判断版本效力；为空表示按运行时刻。 */
  queryAt: string | null;
}

export interface CaseDetails {
  expectedAnswer?: string;
  requiredFacts: string[];
  appliesTo?: string;
  queryAt?: string;
}

export interface EvalSummary {
  cases: number;
  evaluated: number;
  prepFailed: number;
  notApplicable: number;
  hit: { num: number; den: number };
  recall: { num: number; den: number };
  recallMacro: number | null;
  p95Ms: number | null;
  /** 证据版本当前不符合条件的用例；在开始上报此项之前保存的运行中不存在。 */
  sourceUnavailable?: number;
}

export interface EvalResult {
  caseId: string;
  question: string;
  label: string;
  mappingFailed: boolean;
  hit: boolean;
  evidenceHit: number;
  evidenceTotal: number;
  latencyMs: number;
  sourceUnavailable?: boolean;
}

export interface EvalRun {
  id: string;
  k: number;
  model: string | null;
  createdAt: string;
  summary: EvalSummary;
  results: EvalResult[];
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

/** 仅当响应体符合契约错误结构时才使用；其他内容均视为不可信。 */
export async function toApiError(res: Response): Promise<ApiError> {
  try {
    const body: unknown = JSON.parse((await res.text()).slice(0, 65_536));
    if (typeof body === "object" && body !== null) {
      const { code, message, requestId } = body as Record<string, unknown>;
      if (isStr(code) && isStr(message)) return new ApiError(res.status, code, message, isStr(requestId) ? requestId : undefined);
    }
  } catch {
    // 回退为下方的通用错误
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
    deleteDocument: (kbId: string, doc: string) => request<void>(`${kb(kbId)}/documents/${e(doc)}`, { method: "DELETE" }),
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
    overview: (kbId: string) => request<OverviewApi>(`${kb(kbId)}/overview`),
    evalCases: (kbId: string) => request<EvalCase[]>(`${kb(kbId)}/eval-cases`),
    createEvalCase: (kbId: string, body: { question: string; label: EvalCase["label"]; source?: string; originMessageId?: string;
      evidence?: EvidenceInput[] } & Partial<CaseDetails>) =>
      request<{ id: string }>(`${kb(kbId)}/eval-cases`, post(body)),
    setEvalEvidence: (kbId: string, caseId: string, evidence: EvidenceInput[]) =>
      request<void>(`${kb(kbId)}/eval-cases/${e(caseId)}/evidence`, { ...post({ evidence }), method: "PUT" }),
    setEvalCaseDetails: (kbId: string, caseId: string, details: CaseDetails) =>
      request<void>(`${kb(kbId)}/eval-cases/${e(caseId)}/details`, { ...post(details), method: "PUT" }),
    approveEvalCase: (kbId: string, caseId: string) => request<void>(`${kb(kbId)}/eval-cases/${e(caseId)}/approve`, { method: "POST" }),
    runEvaluation: (kbId: string, k: number) => request<EvalRun>(`${kb(kbId)}/eval-runs`, post({ k })),
    latestRun: (kbId: string) => request<EvalRun | undefined>(`${kb(kbId)}/eval-runs/latest`),
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
    updateFeedback: (kbId: string, messageId: string, body: FeedbackUpdate) =>
      request<void>(`${kb(kbId)}/feedback/${e(messageId)}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
    trace: (kbId: string, traceId: string) => request<StoredTrace>(`${kb(kbId)}/traces/${e(traceId)}`),
    chatRequest: (clientRequestId: string) =>
      request<{ status: string; conversationId?: string; messageId?: string; acceptedAt: string }>(`/api/chat/requests/${e(clientRequestId)}`),
    feedbackHistory: (kbId: string, messageId: string) => request<HandlingEntry[]>(`${kb(kbId)}/feedback/${e(messageId)}/history`),
    conversations: () => request<Conversation[]>("/api/conversations"),
    messages: (id: string) => request<HistoryMessage[]>(`/api/conversations/${e(id)}/messages`),
  };
}

export type ApiClient = ReturnType<typeof createClient>;
