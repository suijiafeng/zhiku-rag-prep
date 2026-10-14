import { Alert, Button, Card, Col, Progress, Row, Segmented, Select, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type ApiClient, type FeedbackReason, type FeedbackStatus, type RecordItem, type RecordStats } from "../../api/client";
import { ROOT_CAUSES } from "../evaluation/fixtures";
import { REASON_LABEL } from "../qa/FeedbackBar";
import { EmptyState, LoadingList } from "../../components/StateViews";

type Filter = "all" | "up" | "down" | "none";

const STATUS_LABEL: Record<FeedbackStatus, string> = {
  open: "待处理", in_progress: "处理中", to_verify: "待验证", resolved: "已解决", wont_fix: "无需处理",
};

export function praiseRate(stats: RecordStats) {
  const rated = stats.up + stats.down;
  return rated === 0 ? "—" : `${stats.up}/${rated} · ${((stats.up / rated) * 100).toFixed(1)}%`;
}

export function topReasons(reasons: RecordStats["reasons"]) {
  return (Object.entries(reasons) as [FeedbackReason, number][])
    .sort((a, b) => b[1] - a[1]).slice(0, 3).map(([key, count]) => ({ key, count }));
}

export function RecordsTable({ items, onStatus, onCandidate }: {
  items: RecordItem[];
  onStatus: (messageId: string, status: FeedbackStatus, rootCause: string | undefined) => void;
  onCandidate: (item: RecordItem) => void;
}) {
  return (
    <Table<RecordItem>
      size="small"
      rowKey="messageId"
      pagination={false}
      dataSource={items}
      scroll={{ x: 900 }}
      locale={{ emptyText: "暂无问答记录" }}
      columns={[
        { title: "时间", dataIndex: "createdAt", className: "mono", width: 170, render: (v: string) => new Date(v).toLocaleString("zh-CN") },
        { title: "提问人", dataIndex: "own", width: 80, render: (own: boolean) => (own ? <Tag color="blue">本人</Tag> : <Tag>他人</Tag>) },
        {
          title: "问题",
          dataIndex: "question",
          render: (q: string | null) => q ?? <Typography.Text type="secondary">无权查看他人问答正文</Typography.Text>,
        },
        { title: "引用", dataIndex: "citationCount", className: "mono", width: 70, render: (n: number) => `${n} 篇` },
        {
          title: "反馈",
          key: "feedback",
          width: 150,
          render: (_, r) => !r.rating ? "—" : (
            <>
              <Tag color={r.rating === "up" ? "success" : "error"}>{r.rating === "up" ? "赞" : "踩"}</Tag>
              {r.reason && <span>{REASON_LABEL[r.reason]}</span>}
              {r.comment && <div className="card-unit">{r.comment}</div>}
            </>
          ),
        },
        {
          title: "处理状态 / 根因",
          key: "status",
          render: (_, r) => !r.status ? "—" : (
            <div className="record-status">
              <Select<FeedbackStatus> size="small" value={r.status} aria-label="处理状态"
                onChange={(s) => onStatus(r.messageId, s, r.rootCause ?? undefined)}
                options={(Object.keys(STATUS_LABEL) as FeedbackStatus[]).map((k) => ({ value: k, label: STATUS_LABEL[k] }))} />
              <Select<string> size="small" placeholder="根因" aria-label="根因" className="record-cause"
                {...(r.rootCause ? { value: r.rootCause } : {})}
                onChange={(c) => onStatus(r.messageId, r.status ?? "open", c)}
                options={ROOT_CAUSES.map((c) => ({ value: c, label: c }))} />
            </div>
          ),
        },
        {
          title: "操作",
          key: "op",
          width: 130,
          render: (_, r) => r.rating === "down"
            ? <Button size="small" disabled={!r.question} title={r.question ? "创建评测候选，到评测中心补齐证据后批准" : "无权查看他人问答正文"}
                onClick={() => onCandidate(r)}>加入评测候选</Button>
            : null,
        },
      ]}
    />
  );
}

/** Q&A records and feedback triage (PRD F02) for the selected knowledge base. */
export function RecordsPage({ client, kbId }: { client: ApiClient | undefined; kbId: string | undefined }) {
  const [filter, setFilter] = useState<Filter>("all");
  const [data, setData] = useState<{ items: RecordItem[]; stats: RecordStats; canReadOthers: boolean }>();
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    if (!client || !kbId) return;
    try {
      setData(await client.records(kbId, filter));
      setError(undefined);
    } catch (e) {
      setError(e instanceof ApiError ? (e.status === 403 ? "需要知识库运营角色与该库授权" : `${e.message}（${e.code}）`) : "请求失败");
    }
  }, [client, kbId, filter]);
  useEffect(() => void load(), [load]);

  const [notice, setNotice] = useState<string>();
  const onCandidate = async (item: RecordItem) => {
    if (!client || !kbId || !item.question) return;
    try {
      await client.createEvalCase(kbId, { question: item.question, label: "answerable", source: "feedback" });
      setNotice("已加入评测候选，请到评测中心补齐证据后批准。");
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message}（${e.code}）` : "加入失败");
    }
  };

  const onStatus = async (messageId: string, status: FeedbackStatus, rootCause: string | undefined) => {
    if (!client || !kbId) return;
    try {
      await client.updateFeedback(kbId, messageId, rootCause ? { status, rootCause } : { status });
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message}（${e.code}）` : "更新失败");
    }
    await load();
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">问答记录与反馈</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">用户反馈驱动评测集持续更新 · 他人问答正文需诊断授权</Typography.Text>
        </div>
      </div>
      {(!client || !kbId) && <Alert type="info" showIcon className="scaffold-alert" title="连接本地服务并选择知识库后查看问答记录" description="需要知识库运营角色。本页不展示虚构的用户或问答。" />}
      {error && <Alert type="error" showIcon className="scaffold-alert" title={error} />}
      {notice && <Alert type="success" showIcon closable className="scaffold-alert" title={notice} onClose={() => setNotice(undefined)} />}
      {client && kbId && (
        <Row gutter={[16, 16]}>
          <Col xs={24} xl={17}>
            <Segmented className="doc-filter" value={filter} onChange={(v) => setFilter(v as Filter)}
              options={[{ value: "all", label: "全部" }, { value: "up", label: "赞" }, { value: "down", label: "踩" }, { value: "none", label: "未评价" }]} />
            <Card className="doc-table">
              {!data && !error && <div className="state-pad"><LoadingList /></div>}
              {data?.items.length === 0 && <EmptyState icon="chat" title="暂无问答记录" description="员工在问答工作台或挂件中提问后，记录会出现在这里。" />}
              {data && data.items.length > 0 && <RecordsTable items={data.items} onStatus={(m, s, c) => void onStatus(m, s, c)} onCandidate={(r) => void onCandidate(r)} />}
            </Card>
          </Col>
          <Col xs={24} xl={7}>
            <Card size="small" className="side-card" title="反馈统计 · 近 7 天">
              {data && (
                <>
                  <div className="metric-title">好评率</div>
                  <div className="metric-value">{praiseRate(data.stats)}</div>
                  <div className="card-unit">共 {data.stats.total} 条回答，{data.stats.unrated} 条未评价</div>
                  <div className="metric-title reasons-title">点踩原因 Top 3</div>
                  {topReasons(data.stats.reasons).map((r) => (
                    <div key={r.key} className="reason-row">
                      <span>{REASON_LABEL[r.key]}</span>
                      <Progress percent={data.stats.down ? Math.round((r.count / data.stats.down) * 100) : 0} showInfo={false} size="small" />
                      <span className="mono">{r.count}</span>
                    </div>
                  ))}
                  {topReasons(data.stats.reasons).length === 0 && <Typography.Text type="secondary">暂无点踩</Typography.Text>}
                </>
              )}
            </Card>
            <Card size="small" className="side-card" title="反馈闭环">
              <ol className="proposal-list">
                <li>员工在问答中点赞或点踩，点踩须选原因（已实现）。</li>
                <li>运营在本页按状态和根因处理反馈（已实现）。</li>
                <li>点踩记录加入评测候选，在评测中心补齐原文证据后批准（已实现）。</li>
                <li>修复经回归验证后关闭反馈。</li>
              </ol>
            </Card>
          </Col>
        </Row>
      )}
    </div>
  );
}
