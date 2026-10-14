import { Alert, Input, Modal, Select, Space, Typography } from "antd";
import { useEffect, useState } from "react";
import type { EvalCase, FeedbackStatus, FeedbackUpdate, HandlingEntry } from "../../api/client";
import { ROOT_CAUSES } from "../evaluation/fixtures";

export const STATUS_LABEL: Record<FeedbackStatus, string> = {
  open: "待处理", in_progress: "处理中", to_verify: "待验证", resolved: "已解决", wont_fix: "无需处理",
};

export type ClosingStatus = "resolved" | "wont_fix";

export interface ClosingForm {
  rootCause: string | undefined;
  resolution: string;
  caseId: string | undefined;
}

/** Mirrors the server rule so the form explains what is missing; the server still decides (latest-run check). */
export function closingBody(status: ClosingStatus, form: ClosingForm): { body: FeedbackUpdate } | { error: string } {
  const resolution = form.resolution.trim();
  if (status === "wont_fix") {
    return resolution ? { body: { status, resolution } } : { error: "请填写不处理的理由" };
  }
  if (!form.rootCause) return { error: "请选择根因" };
  if (!resolution) return { error: "请填写修复方案" };
  if (!form.caseId) return { error: "请选择验证用例" };
  return { body: { status, rootCause: form.rootCause, resolution, verificationCaseId: form.caseId } };
}

export function CloseFeedbackModal({ status, initialRootCause, cases, onSubmit, onCancel }: {
  status: ClosingStatus | undefined;
  initialRootCause: string | undefined;
  cases: EvalCase[];
  onSubmit: (body: FeedbackUpdate) => Promise<string | undefined>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<ClosingForm>({ rootCause: initialRootCause, resolution: "", caseId: undefined });
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setForm({ rootCause: initialRootCause, resolution: "", caseId: undefined });
    setError(undefined);
  }, [status, initialRootCause]);
  const approved = cases.filter((c) => c.status === "approved");
  const edit = (change: Partial<ClosingForm>) => {
    setForm({ ...form, ...change });
    setError(undefined);
  };

  const submit = async () => {
    if (!status) return;
    const result = closingBody(status, form);
    if ("error" in result) {
      setError(result.error);
      return;
    }
    setBusy(true);
    setError(await onSubmit(result.body));
    setBusy(false);
  };

  return (
    <Modal open={status !== undefined} title={status === "resolved" ? "标记为已解决" : "标记为无需处理"} okText="确认关闭" cancelText="取消"
      confirmLoading={busy} onOk={() => void submit()} onCancel={onCancel} destroyOnHidden>
      <Space orientation="vertical" className="close-form">
        {status === "resolved" && (
          <>
            <Typography.Text type="secondary">
              须选择一条已批准的评测用例，且它在本库最近一次评测运行中全部证据都被召回。这只验证检索，不证明回答正确。
            </Typography.Text>
            <Select<string> placeholder="根因" aria-label="根因" {...(form.rootCause ? { value: form.rootCause } : {})} onChange={(rootCause) => edit({ rootCause })}
              options={ROOT_CAUSES.map((c) => ({ value: c, label: c }))} />
            <Select<string> placeholder={approved.length ? "验证用例" : "暂无已批准的评测用例"} aria-label="验证用例" {...(form.caseId ? { value: form.caseId } : {})}
              disabled={!approved.length} onChange={(caseId) => edit({ caseId })}
              options={approved.map((c) => ({ value: c.id, label: c.question }))} />
          </>
        )}
        <Input.TextArea rows={3} maxLength={1000} showCount aria-label={status === "resolved" ? "修复方案" : "不处理的理由"}
          placeholder={status === "resolved" ? "修复方案，如：发布新版本并重建索引" : "不处理的理由，如：超出试点范围，已转人工渠道"}
          value={form.resolution} onChange={(e) => edit({ resolution: e.target.value })} />
        {error && <Alert type="error" showIcon title={error} />}
      </Space>
    </Modal>
  );
}

export function HandlingList({ entries }: { entries: HandlingEntry[] }) {
  if (entries.length === 0) return <Typography.Text type="secondary">暂无处理记录</Typography.Text>;
  return (
    <ol className="handling-list">
      {entries.map((h, i) => (
        <li key={i}>
          <span className="mono">{new Date(h.changedAt).toLocaleString("zh-CN")}</span> · {STATUS_LABEL[h.status]}
          {h.rootCause && ` · ${h.rootCause}`}
          {h.resolution && <div className="card-unit">{h.resolution}</div>}
          {h.verificationCaseId && <div className="card-unit">验证用例 {h.verificationCaseId.slice(0, 8)}</div>}
        </li>
      ))}
    </ol>
  );
}
