import { Button, Input, Select, Space, Typography } from "antd";
import { useState } from "react";
import type { FeedbackInfo, FeedbackReason } from "../../api/client";

export const REASON_LABEL: Record<FeedbackReason, string> = {
  WRONG: "答错了",
  MISSING: "没答到",
  BAD_CITATION: "引用不对",
  SHOULD_REFUSE: "应该拒答",
  OUTDATED: "内容过期",
};

/** Asker's own feedback (PRD F01). A dislike needs a reason; changes are versioned on the server. */
export function FeedbackBar({ current, onSubmit }: {
  current: FeedbackInfo | null;
  onSubmit: (body: { rating: "up" | "down"; reason?: FeedbackReason; comment?: string }) => Promise<void>;
}) {
  const [picking, setPicking] = useState(false);
  const [reason, setReason] = useState<FeedbackReason>();
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (body: { rating: "up" | "down"; reason?: FeedbackReason; comment?: string }) => {
    setBusy(true);
    try {
      await onSubmit(body);
      setPicking(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="feedback-bar" onClick={(e) => e.stopPropagation()}>
      <Space size={4}>
        <Button size="small" type={current?.rating === "up" ? "primary" : "default"} aria-pressed={current?.rating === "up"}
          loading={busy && !picking} onClick={() => void submit({ rating: "up" })}>👍 赞</Button>
        <Button size="small" type={current?.rating === "down" ? "primary" : "default"} danger={current?.rating === "down"}
          aria-pressed={current?.rating === "down"} onClick={() => setPicking((p) => !p)}>👎 踩</Button>
        {current?.rating === "down" && current.reason && <Typography.Text type="secondary">{REASON_LABEL[current.reason]}</Typography.Text>}
      </Space>
      {picking && (
        <Space wrap className="feedback-form">
          <Select<FeedbackReason> size="small" placeholder="原因" className="feedback-reason" onChange={setReason} aria-label="点踩原因"
            options={(Object.keys(REASON_LABEL) as FeedbackReason[]).map((k) => ({ value: k, label: REASON_LABEL[k] }))} />
          <Input size="small" placeholder="补充说明（可选，500 字内）" maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} aria-label="补充说明" />
          <Button size="small" type="primary" disabled={!reason} loading={busy}
            onClick={() => reason && void submit(comment.trim() ? { rating: "down", reason, comment: comment.trim() } : { rating: "down", reason })}>提交</Button>
        </Space>
      )}
    </div>
  );
}
