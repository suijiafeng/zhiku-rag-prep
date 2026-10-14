import { useEffect, useRef, useState } from "react";
import { createRequestGate } from "../../api/requestGate";
import { ApiError, type ApiClient } from "../../api/client";
import { ErrorState, LoadingList } from "../../components/StateViews";
import { overviewFromApi } from "./overviewMapper";
import { OverviewPage } from "./OverviewPage";
import type { OverviewData } from "./fixtures";

export function LiveOverview({ client, kbId, zone, onNewQuestion }: { client: ApiClient; kbId: string; zone: string; onNewQuestion: () => void }) {
  const [data, setData] = useState<OverviewData>();
  const [error, setError] = useState<ApiError>();
  const gate = useRef(createRequestGate());
  const load = () => {
    const g = gate.current.next();
    setError(undefined);
    client.overview(kbId).then((api) => { if (gate.current.isCurrent(g)) setData(overviewFromApi(api, zone, new Date())); },
      (e: unknown) => { if (gate.current.isCurrent(g)) setError(e instanceof ApiError ? e : new ApiError(0, "NETWORK", "请求失败")); });
  };
  useEffect(() => {
    setData(undefined);
    load();
  }, [client, kbId, zone]);
  if (error) return <div className="page"><ErrorState code={error.code} message={error.message} requestId={error.requestId} onRetry={load} /></div>;
  if (!data) return <div className="page state-pad"><LoadingList rows={8} /></div>;
  return <OverviewPage data={data} live onNewQuestion={onNewQuestion} />;
}
