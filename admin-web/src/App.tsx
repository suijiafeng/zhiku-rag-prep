import { Alert, Avatar, Breadcrumb, ConfigProvider, Input, Layout, Menu } from "antd";
import { useState, type ReactNode } from "react";
import type { ApiClient, Status } from "./api/client";
import { ConnectControl, SessionProvider, useSession } from "./session";
import { LiveDocuments } from "./features/documents/LiveDocuments";
import { LiveQa } from "./features/qa/LiveQa";
import { LiveDebug } from "./features/debug/LiveDebug";
import { SettingsPage } from "./features/settings/SettingsPage";
import { UsersPage } from "./features/users/UsersPage";
import { RecordsPage } from "./features/records/RecordsPage";
import { LoginPage } from "./features/login/LoginPage";
import { LiveOverview } from "./features/overview/LiveOverview";
import { LiveEvaluation } from "./features/evaluation/LiveEvaluation";
import { IntegrationPage } from "./features/integration/IntegrationPage";
import { DocumentsPage } from "./features/documents/DocumentsPage";
import { documentsFixture } from "./features/documents/fixtures";
import { QaWorkbench } from "./features/qa/QaWorkbench";
import { RetrievalDebugPage } from "./features/debug/RetrievalDebugPage";
import { debugFixture } from "./features/debug/fixtures";
import { EvaluationPage } from "./features/evaluation/EvaluationPage";
import { evaluationFixture } from "./features/evaluation/fixtures";
import { qaFixture } from "./features/qa/fixtures";
import { OverviewPage } from "./features/overview/OverviewPage";
import { overviewFixture } from "./features/overview/fixtures";

const NAV = [
  { key: "overview", label: "知识库总览" },
  { key: "documents", label: "文档管理" },
  { key: "qa", label: "问答工作台" },
  { key: "records", label: "问答记录" },
  { key: "debug", label: "检索调试" },
  { key: "evaluation", label: "评测中心" },
  { key: "integration", label: "API 与集成" },
  { key: "users", label: "用户与权限" },
  { key: "settings", label: "系统设置" },
] as const;

type NavKey = (typeof NAV)[number]["key"];

export interface PageContext {
  client?: ApiClient | undefined;
  kbId?: string | undefined;
  kbName?: string | undefined;
  status?: Status | undefined;
  statusError?: string | undefined;
  navigate?: ((key: NavKey) => void) | undefined;
}

/** 实时页面需要已连接的会话并选中知识库；否则显示示例页面并加以标注。 */
export function pageFor(key: NavKey, ctx: PageContext = {}): ReactNode {
  const live = ctx.client && ctx.kbId ? { client: ctx.client, kbId: ctx.kbId } : undefined;
  if (key === "overview") {
    return live
      ? <LiveOverview {...live} zone={ctx.status?.businessTimeZone ?? "Asia/Shanghai"} onNewQuestion={() => ctx.navigate?.("qa")} />
      : <OverviewPage data={overviewFixture} />;
  }
  if (key === "documents") return live ? <LiveDocuments {...live} status={ctx.status} /> : <DocumentsPage data={documentsFixture} />;
  if (key === "qa") return live ? <LiveQa {...live} modelEnabled={ctx.status?.modelEnabled ?? false} /> : <QaWorkbench data={qaFixture} />;
  if (key === "debug") return live ? <LiveDebug {...live} kbName={ctx.kbName ?? ""} /> : <RetrievalDebugPage trace={debugFixture} />;
  if (key === "evaluation") return live ? <LiveEvaluation {...live} /> : <EvaluationPage data={evaluationFixture} />;
  if (key === "users") return <UsersPage client={ctx.client} />;
  if (key === "records") return <RecordsPage client={ctx.client} kbId={ctx.kbId} />;
  if (key === "integration") return <IntegrationPage kbId={ctx.kbId} kbName={ctx.kbName} />;
  return <SettingsPage status={ctx.status} error={ctx.statusError} />;
}

type Mode = "login" | "sample";

export function App({ initialMode = "login" }: { initialMode?: Mode }) {
  return (
    <ConfigProvider button={{ autoInsertSpace: false }} theme={{ token: { colorPrimary: "#2f5bd3", borderRadius: 8 } }}>
      <SessionProvider>
        <Entry initialMode={initialMode} />
      </SessionProvider>
    </ConfigProvider>
  );
}

/** 先登录；会话连接后或用户选择示例数据时才显示控制台。 */
function Entry({ initialMode }: { initialMode: Mode }) {
  const session = useSession();
  const [mode, setMode] = useState<Mode>(initialMode);
  if (!session.client && mode === "login") {
    return <LoginPage onLogin={session.connect} onBrowseSamples={() => setMode("sample")} />;
  }
  return <Shell onLogin={() => setMode("login")} />;
}

/** 按 Figma 画板实现的管理端外壳。连接本地会话前使用示例数据。 */
function Shell({ onLogin }: { onLogin: () => void }) {
  const session = useSession();
  const [active, setActive] = useState<NavKey>("overview");
  const kbName = session.knowledgeBases.find((k) => k.id === session.kbId)?.name;
  const current = NAV.find((n) => n.key === active) ?? NAV[0];

  return (
    <>
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
              <div className="brand-name">{session.client ? "开发令牌" : "未连接"}</div>
              <div className="brand-sub">{session.client ? "本地 JWT · 生产需 IdP" : "示例数据"}</div>
            </div>
          </div>
        </Layout.Sider>
        <Layout>
          <Layout.Header className="shell-header">
            <Breadcrumb items={[{ title: kbName ?? "知识库" }, { title: current.label }]} />
            <div className="shell-tools">
              <Input className="shell-search" placeholder="搜索文档、问答…" disabled aria-label="搜索（未实现）" />
              <ConnectControl onLogin={onLogin} />
            </div>
          </Layout.Header>
          <Layout.Content className="shell-content">
            <Alert
              type="warning"
              showIcon
              className="scaffold-alert"
              title="开发环境，尚未接入生产服务"
              description={session.client
                ? "已连接本地服务：所有页面均为真实数据。SSE validated 仅表示 metadata-only 来源检查，不代表回答正确。"
                : "未登录时页面为虚构示例数据。登录需开发令牌；生产 IdP 未接入。SSE validated 仅表示 metadata-only 来源检查，不代表回答正确。"}
            />
            {pageFor(active, { client: session.client, kbId: session.kbId, kbName, status: session.status, statusError: session.statusError, navigate: setActive })}
          </Layout.Content>
        </Layout>
      </Layout>
    </>
  );
}
