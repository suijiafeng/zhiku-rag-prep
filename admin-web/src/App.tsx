import { Alert, Card, ConfigProvider, Space, Tag, Typography } from "antd";

/** Static smoke page. Deliberately does not fetch, mutate, or authenticate. */
export function App() {
  return (
    <ConfigProvider theme={{ token: { colorPrimary: "#155e75", borderRadius: 10 } }}>
      <main className="scaffold-page">
        <Typography.Text type="secondary">ZHIKU / ENGINEERING SCAFFOLD</Typography.Text>
        <Typography.Title level={1}>知库 · 管理端骨架</Typography.Title>
        <Typography.Paragraph>
          React + TypeScript + Ant Design + Vite。当前页面只验证工程可启动。
        </Typography.Paragraph>
        <Alert
          type="warning"
          showIcon
          title="开发骨架，尚未接入生产服务"
          description="未实现登录、权限、知识库管理、文档上传、检索或模型调用。请勿用于处理真实业务数据。"
        />
        <Space orientation="vertical" size="large" className="scaffold-cards">
          <Card title="模块边界">
            <Space wrap>
              <Tag>KnowledgeBase：待实现</Tag>
              <Tag>Document / Version：待实现</Tag>
              <Tag>IngestionJob：待实现</Tag>
              <Tag>Query / Citations：待实现</Tag>
            </Space>
          </Card>
          <Card title="下一步接入约束">
            <Typography.Paragraph>
              以仓库 API 契约为准；接入业务前必须完成服务端鉴权。浏览器不得保存长期密钥。
            </Typography.Paragraph>
            <Typography.Paragraph>
              SSE 中的 validated 当前仅表示临时约定的 metadata-only 状态，不能等同于回答真实性验证。
            </Typography.Paragraph>
          </Card>
        </Space>
      </main>
    </ConfigProvider>
  );
}
