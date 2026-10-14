import { Alert, Avatar, Breadcrumb, ConfigProvider, Input, Layout, Menu, Result, Tag } from "antd";
import { useState, type ReactNode } from "react";
import { DocumentsPage } from "./features/documents/DocumentsPage";
import { documentsFixture } from "./features/documents/fixtures";
import { QaWorkbench } from "./features/qa/QaWorkbench";
import { RetrievalDebugPage } from "./features/debug/RetrievalDebugPage";
import { debugFixture } from "./features/debug/fixtures";
import { qaFixture } from "./features/qa/fixtures";
import { OverviewPage } from "./features/overview/OverviewPage";
import { overviewFixture } from "./features/overview/fixtures";

const NAV = [
  { key: "overview", label: "知识库总览" },
  { key: "documents", label: "文档管理" },
  { key: "qa", label: "问答工作台" },
  { key: "debug", label: "检索调试" },
  { key: "evaluation", label: "评测中心" },
  { key: "settings", label: "系统设置" },
] as const;

type NavKey = (typeof NAV)[number]["key"];

export function pageFor(key: NavKey): ReactNode {
  if (key === "overview") return <OverviewPage data={overviewFixture} />;
  if (key === "documents") return <DocumentsPage data={documentsFixture} />;
  if (key === "qa") return <QaWorkbench data={qaFixture} />;
  if (key === "debug") return <RetrievalDebugPage trace={debugFixture} />;
  const label = NAV.find((n) => n.key === key)?.label ?? key;
  return <Result status="info" title={`${label}：待实现`} subTitle="该页面已在设计稿中，按任务顺序后续接入。" />;
}

/** Admin shell following the Figma "知识库总览" frame. No fetching, mutation or authentication. */
export function App() {
  const [active, setActive] = useState<NavKey>("overview");
  const current = NAV.find((n) => n.key === active) ?? NAV[0];

  return (
    <ConfigProvider button={{ autoInsertSpace: false }} theme={{ token: { colorPrimary: "#2f5bd3", borderRadius: 8 } }}>
      <Layout className="shell">
        <Layout.Sider width={208} className="shell-sider" breakpoint="lg" collapsedWidth={0}>
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">知</span>
            <div>
              <div className="brand-name">知库 RAG</div>
              <div className="brand-sub">企业知识库问答</div>
            </div>
          </div>
          <Menu
            theme="dark"
            mode="inline"
            className="shell-menu"
            selectedKeys={[active]}
            onClick={({ key }) => setActive(key as NavKey)}
            items={NAV.map((n) => ({ key: n.key, label: n.label, icon: <span className="nav-dot" /> }))}
          />
          <div className="shell-user">
            <Avatar className="shell-avatar">访</Avatar>
            <div>
              <div className="brand-name">未登录</div>
              <div className="brand-sub">身份接入待 T1</div>
            </div>
          </div>
        </Layout.Sider>
        <Layout>
          <Layout.Header className="shell-header">
            <Breadcrumb items={[{ title: "知识库" }, { title: current.label }]} />
            <div className="shell-tools">
              <Input className="shell-search" placeholder="搜索文档、问答…" disabled aria-label="搜索（未实现）" />
              <Tag color="orange">本地骨架 · 示例数据</Tag>
            </div>
          </Layout.Header>
          <Layout.Content className="shell-content">
            <Alert
              type="warning"
              showIcon
              className="scaffold-alert"
              title="开发骨架，尚未接入生产服务"
              description="页面数字均为虚构示例数据；未实现登录、权限、上传、检索或模型调用。SSE validated 仅表示 metadata-only 来源检查，不代表回答正确。"
            />
            {pageFor(active)}
          </Layout.Content>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
}
