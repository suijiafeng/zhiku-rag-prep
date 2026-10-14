import { Alert, Card, Col, Row, Tag, Typography } from "antd";
import type { ReactNode } from "react";
import type { Status } from "../../api/client";

const effectRules = [
  { title: "对话模型与生成参数", body: "新请求使用新版本，进行中的请求保持原版本。" },
  { title: "Embedding 模型 / 维度", body: "需要建立新索引并全量重嵌入，成功后再切换；不能只在下次问答生效。" },
  { title: "解析器 / 切块 / 重叠", body: "对选定范围后台重建索引；不改变业务审批与效力。" },
  { title: "召回 K / 融合 / 重排", body: "先保存为候选配置，评测通过后再发布。" },
];

function Row2({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="param-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/** 实时服务端配置的只读视图（GET /api/status）。编辑需要 candidate/verify/publish 流程，尚未实现。 */
export function SettingsPage({ status, error }: { status: Status | undefined; error: string | undefined }) {
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">系统设置</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">模型 · 切块 · 检索参数 · 当前为只读视图</Typography.Text>
        </div>
      </div>
      {error && <Alert type="error" showIcon className="scaffold-alert" title="无法读取服务状态" description={`请确认本地服务已启动（${error}）。`} />}
      {status && (
        <Row gutter={[16, 16]}>
          <Col xs={24} xl={16}>
            <Card size="small" className="debug-card" title="模型配置">
              <dl className="param-grid">
                <Row2 label="对话模型" value={status.chatModel ?? <Tag>未配置</Tag>} />
                <Row2 label="Embedding 模型" value={status.embeddingModel ?? <Tag color="orange">未配置（文档无法建立检索索引）</Tag>} />
                <Row2 label="Rerank 精排" value={<Tag>未接入</Tag>} />
              </dl>
            </Card>
            <Card size="small" className="debug-card" title="上传与切块">
              <dl className="param-grid">
                <Row2 label="允许格式" value={status.uploadFormats.join(" / ")} />
                <Row2 label="单文件上限" value={`${Math.round(status.maxUploadBytes / 1024 / 1024)} MB`} />
                <Row2 label="切块策略" value="固定长度字符切块 + 重叠（服务端配置 zhiku.ingestion）" />
              </dl>
            </Card>
            <Card size="small" className="debug-card" title="检索参数">
              <dl className="param-grid">
                <Row2 label="检索方式" value={status.retrieval === "exact-vector" ? "授权范围内精确向量检索" : status.retrieval} />
                <Row2 label="融合 / 重排" value="未启用" />
                <Row2 label="业务时区" value={status.businessTimeZone} />
              </dl>
            </Card>
          </Col>
          <Col xs={24} xl={8}>
            <Card size="small" className="side-card" title="生效说明">
              {effectRules.map((r) => (
                <div key={r.title} className="candidate-row">
                  <div>
                    <div>{r.title}</div>
                    <div className="card-unit">{r.body}</div>
                  </div>
                </div>
              ))}
            </Card>
            <Alert
              type="info"
              showIcon
              title="当前页面只读"
              description="配置修改需经过保存候选 → 评测验证 → 发布，并对重建索引单独确认；该流程尚未实现。不提供清空向量数据或批量重建的危险操作。"
            />
          </Col>
        </Row>
      )}
    </div>
  );
}
