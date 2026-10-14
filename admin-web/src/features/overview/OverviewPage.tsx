import { Button, Card, Col, Row, Table, Tag, Typography } from "antd";
import { DailyQuestionsChart } from "./DailyQuestionsChart";
import type { IndexStatus, OverviewData } from "./fixtures";

const statusTag: Record<IndexStatus, { color: string; label: string }> = {
  indexed: { color: "success", label: "已索引" },
  parsing: { color: "warning", label: "解析中" },
  failed: { color: "error", label: "失败" },
};

export function OverviewPage({ data }: { data: OverviewData }) {
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">知识库总览</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            示例数据更新于 {data.updatedAt} · 未接入服务，不会自动刷新
          </Typography.Text>
        </div>
        <Button type="primary" disabled title="问答功能尚未实现">+ 新建问答</Button>
      </div>

      <Row gutter={[16, 16]}>
        {data.metrics.map((m) => (
          <Col key={m.title} xs={24} sm={12} xl={6}>
            <Card className="metric-card">
              <div className="metric-title">{m.title}</div>
              <div className="metric-value">{m.value}</div>
              <div className={m.trend === "up" ? "metric-delta up" : "metric-delta down"}>
                {m.trend === "up" ? "▲" : "▼"} {m.delta}
              </div>
            </Card>
          </Col>
        ))}

        <Col xs={24} xl={16}>
          <Card title={<>近 7 日问答量 <span className="card-unit">单位：次</span></>}>
            <DailyQuestionsChart data={data.dailyQuestions} />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title={<>向量库健康 <Tag color="default">未连接</Tag></>}>
            <dl className="kv-list">
              {data.vectorStore.map((row) => (
                <div key={row.label} className="kv-row">
                  <dt>{row.label}</dt>
                  <dd>{row.value}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </Col>

        <Col xs={24} xl={12}>
          <Card title="最近入库文档" extra={<Typography.Text type="secondary">查看全部 →</Typography.Text>}>
            <Table
              size="small"
              pagination={false}
              rowKey="name"
              dataSource={data.recentDocuments}
              columns={[
                { title: "文档", dataIndex: "name" },
                { title: "切块", dataIndex: "chunks", render: (v: number | null) => v ?? "—" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (s: IndexStatus) => <Tag color={statusTag[s].color}>{statusTag[s].label}</Tag>,
                },
                { title: "更新时间", dataIndex: "updatedAt", className: "mono" },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title="最近问答" extra={<Typography.Text type="secondary">查看全部 →</Typography.Text>}>
            <Table
              size="small"
              pagination={false}
              rowKey="question"
              dataSource={data.recentQuestions}
              columns={[
                { title: "问题", dataIndex: "question" },
                { title: "引用", dataIndex: "citations", render: (n: number) => `${n} 篇` },
                { title: "时间", dataIndex: "askedAt", className: "mono" },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
