import { Alert, Button, Card, Col, Input, Row, Typography } from "antd";
import { useState } from "react";

export function embedSnippet(kbId: string, themeColor: string) {
  return `import { ZhikuWidget } from "@zhiku/widget";

// getAccessToken 由宿主前端调用宿主自己的后端，后端再向知库换取 10 分钟挂件令牌。
<ZhikuWidget
  endpoint="https://<知库服务域名>/api/chat"
  knowledgeBaseId="${kbId}"
  getAccessToken={() => fetch("/host-api/zhiku-token").then((r) => r.json()).then((t) => t.token)}
  themeColor="${themeColor}"
  quickQuestions={["年假有几天?", "报销流程"]}
/>`;
}

export function tokenSnippet(kbId: string) {
  return `# 宿主后端：用当前员工的身份令牌换取挂件令牌（不要在浏览器里保存任何令牌）
POST /api/widget-tokens
Authorization: Bearer <员工身份令牌>
Content-Type: application/json

{"kbId":"${kbId}"}

# 返回 {"token": "...", "expiresAt": "..."}：10 分钟有效，仅限该知识库，只能提问和评价本人回答`;
}

function CodeBlock({ code }: { code: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="code-block">
      <pre>{code}</pre>
      <Button size="small" onClick={() => {
        void navigator.clipboard?.writeText(code).then(() => setCopied(true), () => setCopied(false));
      }}>{copied ? "已复制" : "复制代码"}</Button>
    </div>
  );
}

/** 接入指南仅限真实存在的访问方式：短期 widget token。没有长期密钥。 */
export function IntegrationPage({ kbId, kbName }: { kbId: string | undefined; kbName: string | undefined }) {
  const [theme, setTheme] = useState("#1D5BBF");
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">API 与集成</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">把知识库问答以受控身份嵌入内部系统 · 首版只开放挂件短期令牌</Typography.Text>
        </div>
        <Button type="primary" disabled title="长期 API Key 不在首版范围">+ 新建 Key</Button>
      </div>
      <Alert type="warning" showIcon className="scaffold-alert" title="不提供长期 API Key"
        description="前端代码里不出现任何长期密钥。宿主后端用员工身份换取 10 分钟挂件令牌，令牌只能访问一个知识库、只能提问和评价本人回答。" />
      {!kbId ? (
        <Alert type="info" showIcon title="连接本地服务并选择知识库后生成接入代码" />
      ) : (
        <Row gutter={[16, 16]}>
          <Col xs={24} xl={14}>
            <Card size="small" className="debug-card" title={<>前端嵌入 · {kbName}</>} extra={
              <Input size="small" className="theme-input" value={theme} onChange={(e) => setTheme(e.target.value)} aria-label="主题色" addonBefore="主题色" maxLength={7} />
            }>
              <CodeBlock code={embedSnippet(kbId, theme)} />
              <Typography.Paragraph type="secondary" className="page-sub">主题色只接受 #rgb / #rrggbb，其他值回退默认蓝。</Typography.Paragraph>
            </Card>
            <Card size="small" className="debug-card" title="宿主后端换取挂件令牌">
              <CodeBlock code={tokenSnippet(kbId)} />
            </Card>
          </Col>
          <Col xs={24} xl={10}>
            <Card size="small" className="side-card" title="跨域来源允许列表">
              <Typography.Paragraph>
                宿主页面与知库服务不同源时，需要在服务端 <code>ZHIKU_WIDGET_ORIGINS</code> 中逐个列出宿主来源（逗号分隔）。
                未配置时不开启 CORS；只对 /api/chat 与反馈接口生效，且不携带 Cookie。
              </Typography.Paragraph>
            </Card>
            <Card size="small" className="side-card" title="调用统计与 Webhook · 第二阶段">
              <Typography.Paragraph type="secondary">调用量、成功率、P95 统计与 Webhook 事件推送不在首版范围，以下控件不可用。</Typography.Paragraph>
              <Input disabled placeholder="回调 URL" aria-label="回调 URL（未实现）" />
              <div className="proposal-actions">
                <Button disabled>保存</Button>
              </div>
            </Card>
          </Col>
        </Row>
      )}
    </div>
  );
}
