import type { AccessTokenGetter } from "./contracts.js";

export interface ZhikuWidgetProps {
  /** Same-origin or explicitly trusted backend URL; never a model provider URL. */
  endpoint: string;
  knowledgeBaseId: string;
  /** Optional short-lived, narrowly scoped token. No storage is provided. */
  getAccessToken?: AccessTokenGetter;
  title?: string;
}

/** Integration shell only: no requests, token retrieval, or chat implementation. */
export function ZhikuWidget({ title = "知库助手" }: ZhikuWidgetProps) {
  return (
    <section className="zhiku-widget" aria-label={title}>
      <h2>{title}</h2>
      <p role="status">组件骨架已就绪，问答功能尚未接入。</p>
      <p>当前为本地开发占位，不处理真实业务数据。</p>
    </section>
  );
}
