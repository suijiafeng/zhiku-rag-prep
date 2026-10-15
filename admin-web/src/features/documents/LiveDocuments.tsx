import { Alert, Button, Card, Input, Popconfirm, Space, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { createRequestGate } from "../../api/requestGate";
import { ApiError, type ApiClient, type DeletionInfo, type DocumentInfo, type Status, type VersionInfo } from "../../api/client";
import { IndexStatusTag, type IndexStatus } from "../../components/IndexStatusTag";
import { EmptyState, LoadingList } from "../../components/StateViews";

export const indexState = (s: VersionInfo["indexStatus"]): IndexStatus =>
  s === "ready" ? "indexed" : s === "failed" ? "failed" : s === "processing" ? "parsing" : "pending";

/**
 * "pending" 也包括从未获得内容的版本，因此只在上传或重建索引后立即轮询。
 * 删除清理只在删除后立即轮询，这样清理 worker 停止时不会导致无限轮询。
 */
export function shouldPoll(docs: DocumentInfo[] | undefined, watchBudget: number) {
  const states = docs?.flatMap((d) => d.versions.map((v) => v.indexStatus)) ?? [];
  // 已就绪版本重建时索引状态保持 ready，所以观察期内一律轮询，重建成功或失败都能在页面看到。
  return states.includes("processing") || watchBudget > 0;
}

export function deletionLabel(status: DeletionInfo["status"]) {
  if (status === "done") return "已删除 · 清理完成";
  if (status === "failed") return "访问已停用 · 清理失败";
  return "访问已停用 · 清理进行中";
}

function DeletionTag({ deletion }: { deletion: DeletionInfo }) {
  const color = deletion.status === "failed" ? "red" : deletion.status === "done" ? "default" : "orange";
  return (
    <Space orientation="vertical" size={0}>
      <Tag color={color}>{deletionLabel(deletion.status)}</Tag>
      {deletion.error && <span className="card-unit">{deletion.error}</span>}
    </Space>
  );
}

const WATCH_POLLS = 30;

export function businessDate(instant: string | null, zone: string) {
  if (!instant) return undefined;
  return new Intl.DateTimeFormat("sv-SE", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(instant));
}

function businessState(v: VersionInfo) {
  if (v.withdrawn) return <Tag color="default">已撤回</Tag>;
  if (v.published) return <Tag color="green">已发布</Tag>;
  if (v.approved) return <Tag color="blue">已批准</Tag>;
  return <Tag>草稿</Tag>;
}

const failure = (e: unknown) => (e instanceof ApiError ? `${e.message}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）` : "请求失败");

function NewVersionForm({ onCreate }: { onCreate: (label: string, from: string, until: string) => void }) {
  const [label, setLabel] = useState("");
  const [from, setFrom] = useState("");
  const [until, setUntil] = useState("");
  return (
    <Space wrap className="version-form">
      <Input size="small" placeholder="版本号，如 v3.2" value={label} onChange={(e) => setLabel(e.target.value)} aria-label="版本号" />
      <Input size="small" type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="生效日期" />
      <Input size="small" type="date" value={until} onChange={(e) => setUntil(e.target.value)} aria-label="失效日期（可空）" />
      <Button size="small" disabled={!label.trim() || !from} onClick={() => onCreate(label.trim(), from, until)}>新建版本</Button>
    </Space>
  );
}

/** 来自服务端的所选知识库文档：业务状态与索引状态分开显示。 */
export function LiveDocuments({ client, kbId, status }: { client: ApiClient; kbId: string; status: Status | undefined }) {
  const zone = status?.businessTimeZone ?? "Asia/Shanghai";
  const [docs, setDocs] = useState<DocumentInfo[]>();
  const [error, setError] = useState<string>();
  const [title, setTitle] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadTarget = useRef<{ doc: string; version: string }>(undefined);

  const gate = useRef(createRequestGate());
  const load = useCallback(async () => {
    const g = gate.current.next();
    try {
      const docs = await client.documents(kbId);
      if (gate.current.isCurrent(g)) setDocs(docs);
    } catch (e) {
      if (gate.current.isCurrent(g)) setError(failure(e));
    }
  }, [client, kbId]);

  useEffect(() => {
    setDocs(undefined);
    setError(undefined);
    void load();
  }, [load]);

  const [watch, setWatch] = useState(0);
  const poll = shouldPoll(docs, watch);
  useEffect(() => {
    if (!poll) return undefined;
    const timer = setTimeout(() => {
      setWatch((w) => Math.max(0, w - 1));
      void load();
    }, 2000);
    return () => clearTimeout(timer);
  }, [poll, docs, load]);

  const act = async (fn: () => Promise<unknown>, watchAfter = false) => {
    setError(undefined);
    if (watchAfter) setWatch(WATCH_POLLS);
    try {
      await fn();
    } catch (e) {
      setError(failure(e));
    }
    await load();
  };

  const versionColumns = (doc: DocumentInfo) => [
    { title: "版本", dataIndex: "versionLabel" },
    {
      title: "生效区间（业务日期）",
      key: "interval",
      render: (_: unknown, v: VersionInfo) => `${businessDate(v.effectiveFrom, zone)} → ${businessDate(v.effectiveUntil, zone) ?? "长期"}`,
    },
    { title: "业务状态", key: "business", render: (_: unknown, v: VersionInfo) => businessState(v) },
    {
      title: "索引状态",
      key: "index",
      render: (_: unknown, v: VersionInfo) => (
        <Space orientation="vertical" size={0}>
          <IndexStatusTag status={indexState(v.indexStatus)} />
          {v.indexError && <span className="card-unit">{v.indexError}</span>}
        </Space>
      ),
    },
    {
      title: "操作",
      key: "actions",
      render: (_: unknown, v: VersionInfo) => (
        <Space wrap size={4}>
          <Button size="small" onClick={() => { uploadTarget.current = { doc: doc.id, version: v.id }; fileInput.current?.click(); }}>上传内容</Button>
          <Button size="small" disabled={v.approved || v.withdrawn} onClick={() => act(() => client.transition(kbId, doc.id, v.id, "approve"))}>批准</Button>
          <Button size="small" disabled={!v.approved || v.published || v.withdrawn} onClick={() => act(() => client.transition(kbId, doc.id, v.id, "publish"))}>发布</Button>
          <Popconfirm
            title="撤回该版本？"
            description="立即停止检索、引用与问答，依赖它的历史回答将被隐藏；撤回后不能再批准或发布该版本。"
            okText="确认撤回"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            disabled={v.withdrawn}
            onConfirm={() => act(() => client.transition(kbId, doc.id, v.id, "withdraw"))}
          >
            <Button size="small" danger disabled={v.withdrawn}>撤回</Button>
          </Popconfirm>
          <Button size="small" disabled={v.indexStatus === "pending" || v.indexStatus === "processing"} onClick={() => act(() => client.reindex(kbId, doc.id, v.id), true)}>重建索引</Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">文档管理</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            已连接本地服务 · 格式 {status?.uploadFormats.join(" / ") ?? "—"} · 单文件上限 {status ? Math.round(status.maxUploadBytes / 1048576) : "—"} MB · 业务时区 {zone}
          </Typography.Text>
        </div>
        <Space>
          <Input placeholder="新文档标题" value={title} onChange={(e) => setTitle(e.target.value)} aria-label="新文档标题" />
          <Button type="primary" disabled={!title.trim()} onClick={() => act(async () => { await client.createDocument(kbId, title.trim()); setTitle(""); })}>新建文档</Button>
        </Space>
      </div>
      {error && <Alert type="error" showIcon closable className="scaffold-alert" title="操作失败" description={error} onClose={() => setError(undefined)} />}
      <input
        ref={fileInput}
        type="file"
        accept=".md,.markdown,.txt"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          const target = uploadTarget.current;
          e.target.value = "";
          if (file && target) void act(() => client.upload(kbId, target.doc, target.version, file), true);
        }}
      />
      <Card className="doc-table">
        {docs === undefined && !error && <div className="state-pad"><LoadingList /></div>}
        {docs?.length === 0 && (
          <EmptyState icon="doc" title="暂无文档" description="在右上角新建文档，再为版本上传 .md / .txt；版本需批准、发布、处于有效期且索引就绪后才能用于问答。" />
        )}
        {docs && docs.length > 0 && <Table<DocumentInfo>
          rowKey="id"
          dataSource={docs ?? []}
          pagination={false}
          rowClassName={(d) => (d.deletion ? "doc-deleted" : "")}
          expandable={{
            rowExpandable: (doc) => !doc.deletion,
            expandedRowRender: (doc) => (
              <div className="version-panel">
                <Table<VersionInfo> size="small" rowKey="id" pagination={false} dataSource={doc.versions} columns={versionColumns(doc)}
                  locale={{ emptyText: "暂无版本" }} scroll={{ x: 760 }} />
                <NewVersionForm onCreate={(label, from, until) => act(() => client.createVersion(kbId, doc.id,
                  until ? { versionLabel: label, effectiveFrom: from, effectiveUntil: until } : { versionLabel: label, effectiveFrom: from }))} />
              </div>
            ),
          }}
          columns={[
            { title: "文档", dataIndex: "title" },
            { title: "版本数", key: "count", className: "mono", render: (_, d) => d.versions.length },
            {
              title: "最新版本索引",
              key: "latest",
              render: (_, d) => {
                if (d.deletion) return <DeletionTag deletion={d.deletion} />;
                const latest = d.versions.at(-1);
                return latest ? <IndexStatusTag status={indexState(latest.indexStatus)} /> : "—";
              },
            },
            { title: "创建时间", dataIndex: "createdAt", className: "mono", render: (v: string) => businessDate(v, zone) },
            {
              title: "操作",
              key: "op",
              render: (_, d) => {
                if (d.deletion?.status === "failed") {
                  return <Button size="small" onClick={() => act(() => client.deleteDocument(kbId, d.id), true)}>重试清理</Button>;
                }
                if (d.deletion) return null;
                return (
                  <Popconfirm
                    title="删除该文档的全部版本？"
                    description="立即停止检索、引用与问答，依赖它的历史回答将被隐藏；原文、切块与向量随后在后台清理，不可恢复。数据库备份中的副本不在此清理范围内。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => act(() => client.deleteDocument(kbId, d.id), true)}
                  >
                    <Button size="small" danger>删除</Button>
                  </Popconfirm>
                );
              },
            },
          ]}
        />}
      </Card>
    </div>
  );
}
