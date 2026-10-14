import { Alert, Button, Skeleton } from "antd";
import type { ReactNode } from "react";

/** Shared in-page states (design page 07). Retry repeats the user's own action; there is no "upgrade" path. */
const ICONS = { doc: "📄", chat: "💬", search: "🔍", chart: "📊" } as const;

export function EmptyState({ icon, title, description, action }: {
  icon: keyof typeof ICONS;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state" role="status">
      <span className="empty-icon" aria-hidden="true">{ICONS[icon]}</span>
      <div className="empty-title">{title}</div>
      <div className="empty-desc">{description}</div>
      {action}
    </div>
  );
}

export function LoadingList({ rows = 4 }: { rows?: number }) {
  return <Skeleton active title={false} paragraph={{ rows }} />;
}

export function errorTitle(code: string) {
  if (code === "DEPENDENCY_UNAVAILABLE" || code === "MODEL_UNAVAILABLE" || code === "HTTP_503") return "依赖服务暂不可用";
  if (code === "RATE_LIMITED" || code === "HTTP_429") return "请求过多";
  if (code === "FORBIDDEN" || code === "ACCESS_REVOKED") return "没有访问权限";
  if (code === "UNAUTHENTICATED") return "登录已失效";
  if (code === "NO_EVIDENCE") return "知识库中没有足够依据";
  if (code === "SOURCE_UNAVAILABLE") return "来源已变更";
  if (code === "NETWORK" || code === "STREAM_INCOMPLETE") return "连接中断";
  return "请求失败";
}

export function ErrorState({ code, message, requestId, onRetry }: {
  code: string;
  message: string;
  requestId?: string | undefined;
  onRetry?: (() => void) | undefined;
}) {
  const transient = ["DEPENDENCY_UNAVAILABLE", "MODEL_UNAVAILABLE", "HTTP_503", "RATE_LIMITED", "HTTP_429", "NETWORK", "STREAM_INCOMPLETE"].includes(code);
  return (
    <Alert
      type={transient ? "warning" : "error"}
      showIcon
      title={`${errorTitle(code)}（${code}）`}
      description={<>{message}{requestId && <div className="card-unit">请求编号 {requestId}</div>}</>}
      action={onRetry ? <Button size="small" onClick={onRetry}>重试</Button> : undefined}
    />
  );
}
