import { Button, Input, Modal, Select, Space, Tag, Typography } from "antd";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiError, createClient, type ApiClient, type Fetch, type KnowledgeBase, type Status } from "./api/client";

export interface Session {
  client: ApiClient | undefined;
  knowledgeBases: KnowledgeBase[];
  kbId: string | undefined;
  setKbId: (id: string) => void;
  status: Status | undefined;
  statusError: string | undefined;
  connect: (token: string) => Promise<string | undefined>;
  disconnect: () => void;
}

const disconnected: Session = {
  client: undefined, knowledgeBases: [], kbId: undefined, setKbId: () => {}, status: undefined, statusError: undefined,
  connect: async () => "未初始化", disconnect: () => {},
};

const SessionContext = createContext<Session>(disconnected);
export const useSession = () => useContext(SessionContext);

const describe = (e: unknown) =>
  e instanceof ApiError ? (e.status === 401 ? "令牌无效或已过期" : `${e.code}${e.requestId ? ` · ${e.requestId}` : ""}`) : "无法连接本地服务";

/** Holds the bearer token in React state only: it is gone after a reload and never written to storage. */
export function SessionProvider({ children, fetchImpl }: { children: ReactNode; fetchImpl?: Fetch }) {
  const [client, setClient] = useState<ApiClient>();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string>();
  const [status, setStatus] = useState<Status>();
  const [statusError, setStatusError] = useState<string>();

  useEffect(() => {
    createClient(undefined, fetchImpl).status().then(setStatus, (e: unknown) => setStatusError(describe(e)));
  }, [fetchImpl]);

  const connect = useCallback(async (token: string) => {
    const next = createClient(token.trim(), fetchImpl);
    try {
      const kbs = await next.knowledgeBases();
      setClient(next);
      setKnowledgeBases(kbs);
      setKbId(kbs[0]?.id);
      return undefined;
    } catch (e) {
      return describe(e);
    }
  }, [fetchImpl]);

  const disconnect = useCallback(() => {
    setClient(undefined);
    setKnowledgeBases([]);
    setKbId(undefined);
  }, []);

  const value = useMemo<Session>(() => ({ client, knowledgeBases, kbId, setKbId, status, statusError, connect, disconnect }),
    [client, knowledgeBases, kbId, status, statusError, connect, disconnect]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function ConnectControl() {
  const { client, knowledgeBases, kbId, setKbId, connect, disconnect } = useSession();
  const [open, setOpen] = useState(false);
  const [token, setToken] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  if (client) {
    return (
      <Space>
        <Tag color="green">已连接本地服务</Tag>
        <Select
          size="small"
          className="kb-select"
          {...(kbId ? { value: kbId } : {})}
          placeholder="无已授权知识库"
          onChange={setKbId}
          options={knowledgeBases.map((k) => ({ value: k.id, label: k.name }))}
          aria-label="选择知识库"
        />
        <Button size="small" onClick={disconnect}>断开</Button>
      </Space>
    );
  }
  return (
    <>
      <Tag color="orange">本地骨架 · 示例数据</Tag>
      <Button size="small" type="primary" onClick={() => setOpen(true)}>连接本地服务</Button>
      <Modal
        open={open}
        title="连接本地服务"
        okText="连接"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => setOpen(false)}
        onOk={async () => {
          setBusy(true);
          const failure = await connect(token);
          setBusy(false);
          setError(failure);
          if (!failure) {
            setToken("");
            setOpen(false);
          }
        }}
      >
        <Typography.Paragraph type="secondary">
          粘贴开发令牌（node scripts/dev-token.mjs 签发）。令牌只保存在当前页面内存中，刷新即失效。
        </Typography.Paragraph>
        <Input.Password value={token} onChange={(e) => setToken(e.target.value)} placeholder="Bearer 令牌" aria-label="开发令牌" autoComplete="off" />
        {error && <Typography.Text type="danger">{error}</Typography.Text>}
      </Modal>
    </>
  );
}
