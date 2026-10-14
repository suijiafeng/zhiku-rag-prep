import { Button, Divider, Input, Typography } from "antd";
import { useState } from "react";

const FACTS = [
  { title: "授权范围内检索", body: "先按知识库授权与制度效力筛选版本，再做向量检索" },
  { title: "引用可核对", body: "每个回答标注来源版本，提交前检查引用编号" },
  { title: "撤权即时生效", body: "撤销授权后，检索、引用与历史回答一并停止披露" },
];

/**
 * Entry screen. Only login paths that exist are offered: a locally issued dev token now, enterprise SSO later.
 * No password form, no "remember me" (tokens live only in page memory), no password recovery.
 */
export function LoginPage({ onLogin, onBrowseSamples }: {
  onLogin: (token: string) => Promise<string | undefined>;
  onBrowseSamples: () => void;
}) {
  const [token, setToken] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const login = async () => {
    setBusy(true);
    const failure = await onLogin(token);
    setBusy(false);
    setError(failure);
    if (!failure) setToken("");
  };

  return (
    <div className="login">
      <aside className="login-brand">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">知</span>
          <div>
            <div className="login-title">知库 RAG</div>
            <div className="brand-sub">企业知识库问答系统</div>
          </div>
        </div>
        <ul className="login-facts">
          {FACTS.map((f) => (
            <li key={f.title}>
              <span className="login-check" aria-hidden="true">✓</span>
              <div>
                <div className="brand-name">{f.title}</div>
                <div className="brand-sub">{f.body}</div>
              </div>
            </li>
          ))}
        </ul>
        <div className="brand-sub">开发环境 · 尚未接入生产服务</div>
      </aside>
      <main className="login-main">
        <section className="login-card" aria-label="登录">
          <Typography.Title level={3} className="page-title">登录管理控制台</Typography.Title>
          <Typography.Text type="secondary">身份来自登录令牌；用户与角色由身份提供方管理。</Typography.Text>
          <Button block size="large" disabled className="login-sso" title="企业 SSO 尚未接入">企业身份登录（SSO 未接入）</Button>
          <Divider plain>本地开发</Divider>
          <label className="login-label" htmlFor="dev-token">开发令牌</label>
          <Input.Password id="dev-token" aria-label="开发令牌" value={token} onChange={(e) => setToken(e.target.value)} placeholder="node scripts/dev-token.mjs 签发"
            autoComplete="off" onPressEnter={() => token.trim() && void login()} />
          {error && <Typography.Text type="danger">{error}</Typography.Text>}
          <Button type="primary" block size="large" loading={busy} disabled={!token.trim()} onClick={() => void login()}>登录</Button>
          <Typography.Text type="secondary" className="page-sub">令牌只保存在当前页面内存中，刷新或断开即失效。</Typography.Text>
          <Button type="link" onClick={onBrowseSamples}>以示例数据浏览</Button>
        </section>
      </main>
    </div>
  );
}
