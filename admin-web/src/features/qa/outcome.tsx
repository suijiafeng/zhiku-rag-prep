import { Alert } from "antd";
import type { TurnOutcome } from "../../api/client";
import { errorTitle } from "../../components/StateViews";

export const OUTCOME_LABEL: Record<TurnOutcome, string> = { complete: "已完成", refused: "已拒答", cancelled: "已中断", failed: "失败" };

/** 未完成轮次的历史条目：服务端保留了问题和结果，从不保留草稿。 */
export function OutcomeNote({ status, errorCode }: { status: TurnOutcome; errorCode: string | null }) {
  const detail = status === "cancelled" ? "生成被停止" : errorTitle(errorCode ?? "");
  return (
    <Alert type={status === "refused" ? "info" : "warning"} showIcon
      title={`${OUTCOME_LABEL[status]}：${detail}${errorCode ? `（${errorCode}）` : ""}`}
      description="未保存回答内容，也不会作为后续对话的上下文。" />
  );
}
