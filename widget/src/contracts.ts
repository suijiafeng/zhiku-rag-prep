/** 暂定的未来契约；实现前需与根目录 API 规范同步。 */
export interface Citation {
  documentId: string;
  versionId: string;
  title: string;
  excerpt: string;
}

export type ChatEvent =
  | { event: "draft"; data: { text: string; seq?: number } }
  | { event: "validated"; data: { citations: Citation[]; validation: "metadata-only" } }
  | { event: "done"; data: { status: "complete"; conversationId?: string; messageId?: string } }
  | { event: "error"; data: { code: string; message: string; requestId: string; retryable?: boolean } };

export interface ChatRequest {
  question: string;
  kbId: string;
  /** 在同一知识库中继续自己的会话。 */
  conversationId?: string;
  /** 每个问题一个 id；省略时自动生成。服务端只受理一次，重复提交不再回答。 */
  clientRequestId?: string;
}

/** 由未来的适配器按请求获取。不得持久化或记录返回的 token。 */
export type AccessTokenGetter = () => Promise<string | undefined>;
