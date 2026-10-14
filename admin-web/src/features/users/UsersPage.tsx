import { Alert, Button, Card, Col, Input, Popconfirm, Row, Space, Table, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type AdminKnowledgeBase, type ApiClient, type Grant, type KbAction, type Me, type Role, type RolePolicy, type Rule } from "../../api/client";

const ROLE_LABEL: Record<Role, string> = { IT_ADMIN: "IT 管理员", KB_OPERATOR: "知识库运营", EMPLOYEE: "普通员工" };
const ACTION_LABEL: Record<KbAction, string> = {
  ASK_AND_CITE: "问答、引用与原文",
  MANAGE_DOCUMENTS: "文档上传 / 发布 / 撤回",
  DEBUG_AND_EVALUATE: "检索调试与评测",
  READ_OTHERS_CONVERSATIONS: "查看他人问答正文",
};
const RULE_LABEL: Record<Rule, string> = { ALLOWED: "✓", REQUIRES_EXPLICIT_GRANT: "需显式授权", NEVER: "—" };

export const isUuid = (v: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.trim());

const failure = (e: unknown) => (e instanceof ApiError ? `${e.message}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）` : "请求失败");

/** 由 GET /api/policy/roles 渲染，因此页面永远不会与服务端的 RolePolicy 脱节。 */
export function RoleMatrix({ policy }: { policy: RolePolicy }) {
  return (
    <table className="role-matrix">
      <thead>
        <tr>
          <th>操作（还需知识库授权）</th>
          {policy.roles.map((r) => <th key={r}>{ROLE_LABEL[r]}</th>)}
        </tr>
      </thead>
      <tbody>
        {policy.actions.map((a) => (
          <tr key={a}>
            <td>{ACTION_LABEL[a]}</td>
            {policy.roles.map((r) => {
              const rule = policy.rules[r][a];
              return <td key={r} className={`rule-${rule.toLowerCase()}`}>{RULE_LABEL[rule]}</td>;
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Notes() {
  return (
    <Card size="small" className="debug-card" title="说明">
      <ul className="proposal-list">
        <li>用户目录、邀请、停用和最后登录由身份提供方负责，尚未接入；本页只管理知识库授权，用户以身份提供方的用户 ID 标识。</li>
        <li>角色来自登录令牌，名称为 PRD 建议映射，待业务确认（D02）。"需显式授权"的操作目前一律拒绝。</li>
        <li>授权与撤销在下一次请求立即生效，并写入审计日志；撤权后历史回答会按来源依赖隐藏。</li>
      </ul>
    </Card>
  );
}

function Members({ client, kb, onChanged }: { client: ApiClient; kb: AdminKnowledgeBase; onChanged: () => void }) {
  const [grants, setGrants] = useState<Grant[]>();
  const [userId, setUserId] = useState("");
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    try {
      setGrants(await client.grants(kb.id));
    } catch (e) {
      setError(failure(e));
    }
  }, [client, kb.id]);
  useEffect(() => void load(), [load]);

  const act = async (fn: () => Promise<unknown>) => {
    setError(undefined);
    try {
      await fn();
      setUserId("");
    } catch (e) {
      setError(failure(e));
    }
    await load();
    onChanged();
  };

  return (
    <Card size="small" className="debug-card" title={<>成员 · {kb.name}</>}>
      {error && <Alert type="error" showIcon className="scaffold-alert" title={error} />}
      <Space wrap className="version-form">
        <Input className="debug-input" placeholder="用户 ID（UUID，来自身份提供方）" value={userId} onChange={(e) => setUserId(e.target.value)} aria-label="用户 ID" />
        <Button type="primary" disabled={!isUuid(userId)} onClick={() => act(() => client.grant(kb.id, userId.trim()))}>授权</Button>
      </Space>
      <Table<Grant>
        size="small"
        rowKey="userId"
        pagination={false}
        loading={grants === undefined && !error}
        dataSource={grants ?? []}
        locale={{ emptyText: "暂无成员" }}
        columns={[
          { title: "用户 ID", dataIndex: "userId", className: "mono" },
          { title: "授权时间", dataIndex: "grantedAt", className: "mono", render: (v: string) => new Date(v).toLocaleString("zh-CN") },
          {
            title: "操作",
            key: "op",
            render: (_, g) => (
              <Popconfirm
                title="撤销该用户对此知识库的访问？"
                description="下一次请求立即生效；该用户基于此库的历史回答将被隐藏。"
                okText="撤销"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => act(() => client.revoke(kb.id, g.userId))}
              >
                <Button size="small" danger>撤销</Button>
              </Popconfirm>
            ),
          },
        ]}
      />
    </Card>
  );
}

/** 面向 IT 管理员的知识库授权，以及实时角色矩阵。 */
export function UsersPage({ client }: { client: ApiClient | undefined }) {
  const [me, setMe] = useState<Me>();
  const [policy, setPolicy] = useState<RolePolicy>();
  const [kbs, setKbs] = useState<AdminKnowledgeBase[]>();
  const [selected, setSelected] = useState<string>();
  const [name, setName] = useState("");
  const [error, setError] = useState<string>();
  const admin = me?.roles.includes("IT_ADMIN") ?? false;

  const loadKbs = useCallback(async () => {
    if (!client) return;
    try {
      const list = await client.adminKnowledgeBases();
      setKbs(list);
      setSelected((s) => s ?? list[0]?.id);
    } catch (e) {
      setError(failure(e));
    }
  }, [client]);

  useEffect(() => {
    if (!client) return;
    Promise.all([client.me(), client.rolePolicy()]).then(([m, p]) => {
      setMe(m);
      setPolicy(p);
      if (m.roles.includes("IT_ADMIN")) void loadKbs();
    }, (e: unknown) => setError(failure(e)));
  }, [client, loadKbs]);

  const create = async () => {
    if (!client) return;
    setError(undefined);
    try {
      const created = await client.createKnowledgeBase(name.trim());
      setName("");
      setSelected(created.id);
    } catch (e) {
      setError(failure(e));
    }
    await loadKbs();
  };

  const current = kbs?.find((k) => k.id === selected);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">用户与权限</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">知识库级授权 · 角色来自登录令牌 · 用户目录由身份提供方管理</Typography.Text>
        </div>
      </div>
      {!client && <Alert type="info" showIcon className="scaffold-alert" title="连接本地服务后可管理授权" description="需要带 IT_ADMIN 角色的开发令牌。用户资料来自身份提供方，本页不展示虚构用户。" />}
      {error && <Alert type="error" showIcon closable className="scaffold-alert" title={error} onClose={() => setError(undefined)} />}
      {client && me && !admin && <Alert type="warning" showIcon className="scaffold-alert" title="需要 IT 管理员角色才能管理授权" />}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          {admin && (
            <Card size="small" className="debug-card" title="知识库" extra={
              <Space>
                <Input size="small" placeholder="新知识库名称" value={name} onChange={(e) => setName(e.target.value)} aria-label="新知识库名称" maxLength={100} />
                <Button size="small" type="primary" disabled={!name.trim()} onClick={() => void create()}>新建</Button>
              </Space>
            }>
              <Table<AdminKnowledgeBase>
                size="small"
                rowKey="id"
                pagination={false}
                loading={kbs === undefined && !error}
                dataSource={kbs ?? []}
                rowClassName={(k) => (k.id === selected ? "row-selected" : "")}
                onRow={(k) => ({ onClick: () => setSelected(k.id) })}
                locale={{ emptyText: "本企业还没有知识库" }}
                columns={[
                  { title: "名称", dataIndex: "name" },
                  { title: "成员数", dataIndex: "memberCount", className: "mono" },
                  { title: "创建时间", dataIndex: "createdAt", className: "mono", render: (v: string) => new Date(v).toLocaleDateString("zh-CN") },
                ]}
              />
            </Card>
          )}
          {admin && client && current && <Members key={current.id} client={client} kb={current} onChanged={() => void loadKbs()} />}
          <Notes />
        </Col>
        <Col xs={24} xl={10}>
          <Card size="small" className="debug-card" title="角色权限矩阵" extra={<span className="card-unit">来自服务端策略</span>}>
            {policy ? <RoleMatrix policy={policy} /> : <Typography.Text type="secondary">连接后读取服务端策略。</Typography.Text>}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
