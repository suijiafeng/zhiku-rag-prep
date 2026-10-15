import { Alert, Button, Card, Input, InputNumber, Popconfirm, Space, Tag, Typography } from "antd";
import { useCallback, useEffect, useState, useRef } from "react";
import { createRequestGate } from "../../api/requestGate";
import { ApiError, type ApiClient, type ConfigCandidateBody, type ConfigOverview, type KnowledgeBaseConfig } from "../../api/client";
import { LoadingList, errorTitle } from "../../components/StateViews";

export interface ConfigForm {
  retrievalK: number;
  contextChunks: number;
  handoffChannel: string;
  promptAddendum: string;
  note: string;
}

export function candidateBody(form: ConfigForm, revision: number | undefined): ConfigCandidateBody {
  return {
    retrievalK: form.retrievalK,
    contextChunks: form.contextChunks,
    ...(form.handoffChannel.trim() ? { handoffChannel: form.handoffChannel.trim() } : {}),
    ...(form.promptAddendum.trim() ? { promptAddendum: form.promptAddendum.trim() } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    ...(revision === undefined ? {} : { expectedRevision: revision }),
  };
}

export function publishBlockers(candidate: KnowledgeBaseConfig | undefined, dirty = false): string[] {
  if (!candidate) return ["尚无候选配置"];
  if (dirty) return ["有未保存的修改，请先保存并重新验证"];
  return candidate.validatedAt ? [] : ["尚未验证"];
}

/** 编辑中的表单是否与已保存的候选不同：有差异时验证和发布作用的是旧内容，必须先保存。 */
export function isDirty(form: ConfigForm, saved: ConfigForm): boolean {
  return form.retrievalK !== saved.retrievalK || form.contextChunks !== saved.contextChunks
    || form.handoffChannel.trim() !== saved.handoffChannel.trim() || form.promptAddendum.trim() !== saved.promptAddendum.trim()
    || form.note.trim() !== saved.note.trim();
}

/** 验证只证明检索可用并记录一次检索评测；回答质量需要人工评测，这里不替它背书。 */
export function validationLabel(validation: KnowledgeBaseConfig["validation"]) {
  if (!validation) return "未验证";
  return `已验证：检索可用，评测 ${validation.cases} 个用例；人工质量未评测`;
}

const formOf = (c: KnowledgeBaseConfig | undefined, defaults: ConfigOverview["defaults"]): ConfigForm => ({
  retrievalK: c?.retrievalK ?? defaults.retrievalK,
  contextChunks: c?.contextChunks ?? defaults.contextChunks,
  handoffChannel: c?.handoffChannel ?? "",
  promptAddendum: c?.promptAddendum ?? "",
  note: c?.note ?? "",
});

function Values({ c }: { c: KnowledgeBaseConfig }) {
  return (
    <div className="card-unit">
      召回 K {c.retrievalK} · 入模证据 {c.contextChunks} 块 · 人工渠道 {c.handoffChannel ?? "（沿用部署配置）"}
      {c.promptAddendum && <div>补充规则：{c.promptAddendum}</div>}
    </div>
  );
}

export function PublishedCard({ published, history, defaults }: {
  published: KnowledgeBaseConfig | null;
  history: KnowledgeBaseConfig[];
  defaults: ConfigOverview["defaults"];
}) {
  const previous = published ? history.find((h) => h.version !== null && h.version < (published.version ?? 0)) : undefined;
  return (
    <Card size="small" className="debug-card" title="当前生效配置">
      {!published ? (
        <div className="card-unit">尚未发布过配置，使用部署默认值：召回 K {defaults.retrievalK} · 入模证据 {defaults.contextChunks} 块 · 人工渠道 {defaults.handoffChannel || "未配置"}</div>
      ) : (
        <>
          <div>
            <Tag color="green">版本 {published.version}</Tag>
            {published.rolledBackFrom !== null && <Tag>回滚自版本 {published.rolledBackFrom}</Tag>}
            <span className="card-unit">发布于 {published.publishedAt ? new Date(published.publishedAt).toLocaleString("zh-CN") : "—"}</span>
          </div>
          <Values c={published} />
          <div className="card-unit">{previous ? `上个版本 ${previous.version}：召回 K ${previous.retrievalK} · 入模证据 ${previous.contextChunks} 块` : "没有上个版本"}</div>
        </>
      )}
      <div className="card-unit">影响范围：本知识库的新问答请求立即使用；进行中的请求保持原版本。不涉及重建索引，不产生模型费用。</div>
    </Card>
  );
}

export function ConfigHistory({ history, onRollback }: { history: KnowledgeBaseConfig[]; onRollback: (version: number) => void }) {
  if (history.length === 0) return <Typography.Text type="secondary">尚无发布记录</Typography.Text>;
  return (
    <ul className="session-list">
      {history.map((h) => (
        <li key={h.id} className="eval-case">
          <div>
            <Tag color={h.status === "published" ? "green" : "default"}>版本 {h.version}{h.status === "published" ? " · 生效中" : ""}</Tag>
            <span className="card-unit">{h.publishedAt ? new Date(h.publishedAt).toLocaleString("zh-CN") : ""}{h.note ? ` · ${h.note}` : ""}</span>
          </div>
          <Values c={h} />
          {h.status === "superseded" && h.version !== null && (
            <Popconfirm title={`回滚到版本 ${h.version}？`} description="会以该版本的内容发布一个新版本，新问答请求立即使用。" okText="回滚" cancelText="取消"
              onConfirm={() => onRollback(h.version ?? 0)}>
              <Button size="small">回滚到此版本</Button>
            </Popconfirm>
          )}
        </li>
      ))}
    </ul>
  );
}

const failure = (e: unknown) => {
  if (!(e instanceof ApiError)) return "请求失败";
  const title = errorTitle(e.code);
  return `${title === "请求失败" ? e.message : `${title}：${e.message}`}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）`;
};

/** PRD C01：保存候选 → 验证 → 发布；他人改过就提示重新加载再确认，不覆盖。 */
export function LiveConfig({ client, kbId }: { client: ApiClient; kbId: string }) {
  const [overview, setOverview] = useState<ConfigOverview>();
  const [form, setForm] = useState<ConfigForm>();
  const [loadError, setLoadError] = useState<string>();
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [busy, setBusy] = useState(false);

  const gate = useRef(createRequestGate());
  /** keepForm：只刷新服务端状态，保留用户尚未保存的表单输入。 */
  const load = useCallback(async (keepForm = false) => {
    const g = gate.current.next();
    setLoadError(undefined);
    try {
      const o = await client.config(kbId);
      if (!gate.current.isCurrent(g)) return;
      setOverview(o);
      if (!keepForm) setForm(formOf(o.candidate ?? o.published ?? undefined, o.defaults));
    } catch (e) {
      if (!gate.current.isCurrent(g)) return;
      setLoadError(failure(e));
    }
  }, [client, kbId]);
  useEffect(() => void load(), [load]);

  const act = async (fn: () => Promise<unknown>, done: string) => {
    setBusy(true);
    setError(undefined);
    setNotice(undefined);
    let failed = false;
    try {
      await fn();
      setNotice(done);
    } catch (e) {
      failed = true;
      if (e instanceof ApiError && e.code === "CONFIG_CONFLICT") setError("候选配置已被他人修改，已刷新到最新版本；你的修改仍保留在表单中，请核对后再保存。");
      else setError(failure(e));
    } finally {
      setBusy(false);
    }
    await load(failed);
  };

  if (!overview || !form) {
    // 首次加载失败要显示原因和重试入口，而不是一直显示加载占位。
    if (loadError) return <Alert type="error" showIcon className="scaffold-alert" title="配置加载失败" description={loadError}
      action={<Button size="small" onClick={() => void load()}>重试</Button>} />;
    return <div className="state-pad"><LoadingList /></div>;
  }
  const candidate = overview.candidate ?? undefined;
  const revision = candidate?.revision;
  const dirty = isDirty(form, formOf(candidate ?? overview.published ?? undefined, overview.defaults));
  const blockers = publishBlockers(candidate, dirty);
  return (
    <>
      {loadError && <Alert type="error" showIcon closable className="scaffold-alert" title={`刷新失败：${loadError}`} onClose={() => setLoadError(undefined)} />}
      {error && <Alert type="error" showIcon closable className="scaffold-alert" title={error} onClose={() => setError(undefined)} />}
      {notice && <Alert type="success" showIcon closable className="scaffold-alert" title={notice} onClose={() => setNotice(undefined)} />}
      <PublishedCard published={overview.published} history={overview.history} defaults={overview.defaults} />
      <Card size="small" className="debug-card" title={candidate ? `候选配置 · 修订 ${candidate.revision}` : "候选配置"}
        extra={dirty && <Tag color="orange">未保存</Tag>}>
        <Space orientation="vertical" className="close-form">
          <Space wrap>
            <InputNumber min={1} max={30} addonBefore="召回 K" value={form.retrievalK} aria-label="召回 K"
              onChange={(v) => setForm({ ...form, retrievalK: v ?? 1 })} />
            <InputNumber min={1} max={overview.defaults.maxContextChunks} addonBefore="入模证据块数" value={form.contextChunks} aria-label="入模证据块数"
              onChange={(v) => setForm({ ...form, contextChunks: v ?? 1 })} />
          </Space>
          <Input maxLength={200} placeholder="人工渠道，如：人事服务台 8001（空则沿用部署配置）" value={form.handoffChannel} aria-label="人工渠道"
            onChange={(e) => setForm({ ...form, handoffChannel: e.target.value })} />
          <Input.TextArea rows={3} maxLength={1000} showCount placeholder="补充提示规则（追加在固定系统提示词之后，可空）" value={form.promptAddendum}
            aria-label="补充提示规则" onChange={(e) => setForm({ ...form, promptAddendum: e.target.value })} />
          <Input maxLength={500} placeholder="修改说明" value={form.note} aria-label="修改说明" onChange={(e) => setForm({ ...form, note: e.target.value })} />
          <div className="card-unit">
            {candidate ? validationLabel(candidate.validation) : "保存后可验证：验证会用候选的召回 K 做一次真实检索并运行检索评测，不调用对话模型。"}
          </div>
          <Space wrap>
            <Button loading={busy} onClick={() => void act(() => client.saveConfigCandidate(kbId, candidateBody(form, revision)), "候选已保存，验证后才能发布")}>保存候选</Button>
            <Button loading={busy} disabled={!candidate || dirty} title={dirty ? blockers.join("；") : undefined}
              onClick={() => candidate && !dirty && void act(() => client.configAction(kbId, "validate", candidate.id, candidate.revision), "验证完成")}>验证</Button>
            <Button type="primary" loading={busy} disabled={blockers.length > 0} title={blockers.join("；")}
              onClick={() => candidate && blockers.length === 0 && void act(() => client.configAction(kbId, "publish", candidate.id, candidate.revision), "已发布，新问答请求立即使用")}>发布</Button>
            {blockers.length > 0 && <span className="card-unit">{blockers.join("；")}</span>}
          </Space>
        </Space>
      </Card>
      <Card size="small" className="debug-card" title="发布历史">
        <ConfigHistory history={overview.history} onRollback={(v) => void act(() => client.rollbackConfig(kbId, v), `已回滚到版本 ${v} 的内容并发布为新版本`)} />
      </Card>
    </>
  );
}
