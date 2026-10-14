import { Alert, Button, Card, Input, Select, Space, Table, Tag } from "antd";
import { useEffect, useRef, useState } from "react";
import { createRequestGate } from "../../api/requestGate";
import { ApiError, type ApiClient, type ConfigOverview, type TraceResult } from "../../api/client";
import { traceFromResult } from "./liveTrace";
import { RetrievalDebugPage } from "./RetrievalDebugPage";
import { compareRuns, configLabel } from "./compare";
import { EmptyState } from "../../components/StateViews";

const CUSTOM = "custom";

/** 版本选项：部署默认值与已发布过的版本；没有配置权限时只能自定义 K。 */
function versionOptions(config: ConfigOverview | undefined) {
  const versions = (config?.history ?? []).map((h) => h.version).filter((v): v is number => v !== null);
  return [{ value: CUSTOM, label: "自定义 K" }, { value: "0", label: configLabel(0) },
    ...versions.map((v) => ({ value: String(v), label: `${configLabel(v)}${config?.published?.version === v ? "（生效中）" : ""}` }))];
}

/**
 * 针对单个问题执行真实的授权检索；不调用模型，不保存问题和正文。
 * 可按某个配置版本再运行，并与上一次运行作为基线对比；再运行生成新的轨迹，不改变或发布配置。
 */
export function LiveDebug({ client, kbId, kbName }: { client: ApiClient; kbId: string; kbName: string }) {
  const [query, setQuery] = useState("");
  const [k, setK] = useState("8");
  const [version, setVersion] = useState(CUSTOM);
  const [config, setConfig] = useState<ConfigOverview>();
  const [result, setResult] = useState<TraceResult>();
  const [baseline, setBaseline] = useState<TraceResult>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const gate = useRef(createRequestGate());
  // 切换知识库后清掉旧结果，迟到的旧请求结果也不再显示。
  useEffect(() => {
    gate.current.invalidate();
    setResult(undefined);
    setBaseline(undefined);
    setError(undefined);
    setBusy(false);
    setVersion(CUSTOM);
    setConfig(undefined);
    const g = gate.current.next();
    client.config(kbId).then((o) => { if (gate.current.isCurrent(g)) setConfig(o); }, () => undefined);
  }, [client, kbId]);

  const run = async () => {
    const g = gate.current.next();
    setBusy(true);
    setError(undefined);
    try {
      const next = version === CUSTOM
        ? await client.retrievalDebug(kbId, query.trim(), Number(k))
        : await client.retrievalDebug(kbId, query.trim(), undefined, Number(version));
      if (!gate.current.isCurrent(g)) return;
      // 同一问题的上一次运行作为基线；问题变了就不再对比。
      setBaseline(result && result.question === next.question ? result : undefined);
      setResult(next);
    } catch (e) {
      if (gate.current.isCurrent(g)) setError(e instanceof ApiError ? `${e.message}（${e.code}）` : "请求失败");
    } finally {
      if (gate.current.isCurrent(g)) setBusy(false);
    }
  };

  const trace = result && traceFromResult(result, kbName, "按当前授权实时计算");
  const rows = result && baseline ? compareRuns(baseline, result) : undefined;
  return (
    <>
      <Card size="small" className="debug-card debug-query">
        <Space wrap>
          <Input className="debug-input" placeholder="输入要调试的问题" value={query} onChange={(e) => setQuery(e.target.value)}
            onPressEnter={() => query.trim() && void run()} aria-label="调试问题" maxLength={2000} />
          <Select className="debug-version" value={version} onChange={setVersion} options={versionOptions(config)} aria-label="检索配置" />
          {version === CUSTOM && (
            <Input className="debug-k" type="number" min={1} max={30} value={k} onChange={(e) => setK(e.target.value)} aria-label="召回数量 K" addonBefore="K" />
          )}
          <Button type="primary" loading={busy} disabled={!query.trim()} onClick={() => void run()}>{result ? "再运行" : "运行检索"}</Button>
        </Space>
        <div className="card-unit">再运行会生成新的轨迹，不覆盖上一次，也不改变或发布配置；只做检索，不调用对话模型，不产生模型费用。</div>
      </Card>
      {error && <Alert type="error" showIcon className="scaffold-alert" title="检索失败" description={error} />}
      {rows && baseline && result && (
        <Card size="small" className="debug-card"
          title={<>对比 · 基线 {configLabel(baseline.configVersion ?? 0)} vs 本次 {configLabel(result.configVersion ?? 0)}
            <span className="card-unit"> 只比较检索候选，不代表回答质量</span></>}>
          <Table size="small" pagination={false} rowKey="chunkId" dataSource={rows} scroll={{ x: 560 }}
            columns={[
              { title: "切块", render: (_, r) => <>{r.title} <span className="card-unit">{r.versionLabel} · #{String(r.seq).padStart(3, "0")}</span></> },
              { title: "基线排名", dataIndex: "baselineRank", className: "mono", render: (n: number | null) => (n === null ? "—" : `#${n}`) },
              { title: "本次排名", dataIndex: "rerunRank", className: "mono", render: (n: number | null) => (n === null ? "—" : `#${n}`) },
              { title: "变化", render: (_, r) => r.baselineRank === null ? <Tag color="green">新增</Tag> : r.rerunRank === null ? <Tag color="orange">落选</Tag>
                : r.baselineRank === r.rerunRank ? "—" : <Tag>{r.baselineRank > r.rerunRank ? "上升" : "下降"}</Tag> },
            ]} />
        </Card>
      )}
      {trace && trace.candidates.length === 0 && (
        <div className="debug-card"><EmptyState icon="search" title="无检索结果" description="没有符合授权与效力的切块：检查文档版本是否已发布、在生效期内且索引就绪。" /></div>
      )}
      {trace ? <RetrievalDebugPage trace={trace} live /> : <Alert type="info" showIcon title="输入问题后运行，查看本次检索的各阶段与候选得分。" />}
    </>
  );
}
