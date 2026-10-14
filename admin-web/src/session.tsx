import { Button, Select, Space, Tag } from "antd";
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

/** 仅在 React state 中保存 bearer token：刷新后即消失，从不写入存储。 */
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

export function ConnectControl({ onLogin }: { onLogin: () => void }) {
  const { client, knowledgeBases, kbId, setKbId, disconnect } = useSession();
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
        <Button size="small" onClick={() => { disconnect(); onLogin(); }}>断开</Button>
      </Space>
    );
  }
  return (
    <>
      <Tag color="orange">示例数据</Tag>
      <Button size="small" type="primary" onClick={onLogin}>登录</Button>
    </>
  );
}
