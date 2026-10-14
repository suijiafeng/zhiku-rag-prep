import { Alert, Button, Card, Col, Modal, Progress, Row, Segmented, Select, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type ApiClient, type EvalCase, type FeedbackReason, type FeedbackStatus, type FeedbackUpdate, type HandlingEntry, type RecordItem, type RecordStats } from "../../api/client";
import { CloseFeedbackModal, HandlingList, STATUS_LABEL, type ClosingStatus } from "./CloseFeedback";
import { ROOT_CAUSES } from "../evaluation/fixtures";
import { REASON_LABEL } from "../qa/FeedbackBar";
import { OUTCOME_LABEL } from "../qa/outcome";
import { EmptyState, LoadingList } from "../../components/StateViews";

type Filter = "all" | "up" | "down" | "none";

export function praiseRate(stats: RecordStats) {
  const rated = stats.up + stats.down;
  return rated === 0 ? "—" : `${stats.up}/${rated} · ${((stats.up / rated) * 100).toFixed(1)}%`;
}

export function topReasons(reasons: RecordStats["reasons"]) {
  return (Object.entries(reasons) as [FeedbackReason, number][])
    .sort((a, b) => b[1] - a[1]).slice(0, 3).map(([key, count]) => ({ key, count }));
}

/** The server explains in English; the one expected refusal is restated in Chinese, keeping the request id. */
export function closeFailure(e: unknown, status: FeedbackStatus) {
  if (!(e instanceof ApiError)) return "关闭失败";
  const ref = `（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）`;
  if (e.status === 422 && status === "resolved") return `所选用例须已批准，且在本库最近一次评测运行中全部证据被召回${ref}`;
  return `${e.message}${ref}`;
}

/** An evaluation candidate made from a disliked answer keeps the feedback as its origin. */
export function candidateBody(item: RecordItem) {
  return { question: item.question ?? "", label: "answerable" as const, source: "feedback", originMessageId: item.messageId };
}

const isClosing = (s: FeedbackStatus): s is ClosingStatus => s === "resolved" || s === "wont_fix";

export function RecordsTable({ items, onStatus, onClose, onHistory, onCandidate }: {
  items: RecordItem[];
  onStatus: (messageId: string, status: FeedbackStatus, rootCause: string | undefined) => void;
  onClose: (item: RecordItem, status: ClosingStatus) => void;
  onHistory: (item: RecordItem) => void;
  onCandidate: (item: RecordItem) => void;
}) {
  return (
    <Table<RecordItem>
      size="small"
      rowKey="messageId"
      pagination={false}
      dataSource={items}
      scroll={{ x: 1100 }}
      locale={{ emptyText: "暂无问答记录" }}
      columns={[
        { title: "时间", dataIndex: "createdAt", className: "mono", width: 170, render: (v: string) => new Date(v).toLocaleString("zh-CN") },
        { title: "提问人", dataIndex: "own", width: 80, render: (own: boolean) => (own ? <Tag color="blue">本人</Tag> : <Tag>他人</Tag>) },
        {
          title: "问题",
          dataIndex: "question",
          minWidth: 200,
          render: (q: string | null) => q ?? <Typography.Text type="secondary">无权查看他人问答正文</Typography.Text>,
        },
        {
          title: "结果",
          dataIndex: "outcome",
          width: 80,
          render: (o: RecordItem["outcome"], r) => (
            <>
              <Tag color={o === "complete" ? "blue" : o === "refused" ? "default" : "warning"}>{OUTCOME_LABEL[o]}</Tag>
              {r.sourceAvailable === false && <Tag color="red" title="回答或其依据的前序对话的来源已撤回、删除或无法核验">来源已失效</Tag>}
            </>
          ),
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
              <Select<FeedbackStatus> size="small" value={r.status} aria-label="处理状态" popupMatchSelectWidth={false}
                onChange={(s) => (isClosing(s) ? onClose(r, s) : onStatus(r.messageId, s, r.rootCause ?? undefined))}
                options={(Object.keys(STATUS_LABEL) as FeedbackStatus[]).map((k) => ({ value: k, label: STATUS_LABEL[k] }))} />
              <Select<string> size="small" placeholder="根因" aria-label="根因" className="record-cause"
                {...(r.rootCause ? { value: r.rootCause } : {})}
                disabled={isClosing(r.status)}
                onChange={(c) => onStatus(r.messageId, r.status ?? "open", c)}
                options={ROOT_CAUSES.map((c) => ({ value: c, label: c }))} />
              {r.resolution && <div className="card-unit">{r.status === "resolved" ? "修复：" : "理由："}{r.resolution}</div>}
              <Button type="link" size="small" className="record-history" onClick={() => onHistory(r)}>处理记录</Button>
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
      await client.createEvalCase(kbId, candidateBody(item));
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

  const [closing, setClosing] = useState<{ item: RecordItem; status: ClosingStatus }>();
  const [cases, setCases] = useState<EvalCase[]>([]);
  const onClose = async (item: RecordItem, status: ClosingStatus) => {
    setClosing({ item, status });
    if (status === "resolved" && client && kbId) {
      try {
        setCases(await client.evalCases(kbId));
      } catch {
        setCases([]);
      }
    }
  };
  const submitClose = async (body: FeedbackUpdate) => {
    if (!client || !kbId || !closing) return undefined;
    try {
      await client.updateFeedback(kbId, closing.item.messageId, body);
    } catch (e) {
      return closeFailure(e, body.status);
    }
    setClosing(undefined);
    await load();
    return undefined;
  };

  const [history, setHistory] = useState<HandlingEntry[]>();
  const onHistory = async (item: RecordItem) => {
    if (!client || !kbId) return;
    try {
      setHistory(await client.feedbackHistory(kbId, item.messageId));
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message}（${e.code}）` : "读取处理记录失败");
    }
  };

  return (
    <div className="page">
      <CloseFeedbackModal status={closing?.status} messageId={closing?.item.messageId} initialRootCause={closing?.item.rootCause ?? undefined} cases={cases}
        onSubmit={submitClose} onCancel={() => setClosing(undefined)} />
      <Modal open={history !== undefined} title="处理记录" footer={null} onCancel={() => setHistory(undefined)}>
        {history && <HandlingList entries={history} />}
      </Modal>
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
              {data && data.items.length > 0 && <RecordsTable items={data.items} onStatus={(m, s, c) => void onStatus(m, s, c)}
                onClose={(r, s) => void onClose(r, s)} onHistory={(r) => void onHistory(r)} onCandidate={(r) => void onCandidate(r)} />}
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
                <li>关闭须有依据：已解决须填修复方案并选一条在最近一次评测中全部证据被召回的用例；无需处理须填理由（已实现，仅验证检索）。</li>
              </ol>
            </Card>
          </Col>
        </Row>
      )}
    </div>
  );
}
