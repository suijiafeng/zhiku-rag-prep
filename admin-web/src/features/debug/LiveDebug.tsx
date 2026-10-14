import { Alert, Button, Card, Input, Space } from "antd";
import { useState } from "react";
import { ApiError, type ApiClient } from "../../api/client";
import { traceFromResult } from "./liveTrace";
import { RetrievalDebugPage } from "./RetrievalDebugPage";
import type { DebugTrace } from "./fixtures";
import { EmptyState } from "../../components/StateViews";

/** 针对单个问题执行真实的授权检索；不调用模型，不存储任何内容。 */
export function LiveDebug({ client, kbId, kbName }: { client: ApiClient; kbId: string; kbName: string }) {
  const [query, setQuery] = useState("");
  const [k, setK] = useState("8");
  const [trace, setTrace] = useState<DebugTrace>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    setError(undefined);
    try {
      setTrace(traceFromResult(await client.retrievalDebug(kbId, query.trim(), Number(k)), kbName, "按当前授权实时计算"));
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message}（${e.code}）` : "请求失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Card size="small" className="debug-card debug-query">
        <Space wrap>
          <Input className="debug-input" placeholder="输入要调试的问题" value={query} onChange={(e) => setQuery(e.target.value)}
            onPressEnter={() => query.trim() && void run()} aria-label="调试问题" maxLength={2000} />
          <Input className="debug-k" type="number" min={1} max={30} value={k} onChange={(e) => setK(e.target.value)} aria-label="召回数量 K" addonBefore="K" />
          <Button type="primary" loading={busy} disabled={!query.trim()} onClick={() => void run()}>运行检索</Button>
        </Space>
      </Card>
      {error && <Alert type="error" showIcon className="scaffold-alert" title="检索失败" description={error} />}
      {trace && trace.candidates.length === 0 && (
        <div className="debug-card"><EmptyState icon="search" title="无检索结果" description="没有符合授权与效力的切块：检查文档版本是否已发布、在生效期内且索引就绪。" /></div>
      )}
      {trace ? <RetrievalDebugPage trace={trace} live /> : <Alert type="info" showIcon title="输入问题后运行，查看本次检索的各阶段与候选得分。" />}
    </>
  );
}
