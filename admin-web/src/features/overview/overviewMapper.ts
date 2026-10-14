import type { OverviewApi } from "../../api/client";
import type { IndexStatus } from "../../components/IndexStatusTag";
import type { OverviewData } from "./fixtures";

const stamp = (iso: string | Date, zone: string) =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
    .format(new Date(iso));

const indexStatus = (s: OverviewApi["recentDocuments"][number]["indexStatus"]): IndexStatus =>
  s === "ready" ? "indexed" : s === "failed" ? "failed" : s === "processing" ? "parsing" : "pending";

/** 将 GET /overview 映射为概览页模型。只用实测值；不做任何估算。 */
export function overviewFromApi(api: OverviewApi, zone: string, now: Date): OverviewData {
  const rated = api.feedback.up + api.feedback.down;
  const space = api.embeddingSpaces[0];
  return {
    updatedAt: stamp(now, zone),
    metrics: [
      { title: "知识文档总数", value: String(api.documents), delta: `${api.versions} 个版本 · ${api.readyVersions} 个已就绪`, trend: "none" },
      { title: "索引向量", value: String(api.vectors), delta: `${api.chunks} 个切块`, trend: "none" },
      { title: "今日问答", value: String(api.questionsToday), delta: `昨日 ${api.questionsYesterday}`,
        trend: api.questionsToday === api.questionsYesterday ? "none" : api.questionsToday > api.questionsYesterday ? "up" : "down" },
      { title: "好评率（近 7 天）", value: rated ? `${api.feedback.up}/${rated} · ${((api.feedback.up / rated) * 100).toFixed(1)}%` : "—",
        delta: rated ? `${api.feedback.down} 条点踩` : "暂无评价", trend: "none" },
    ],
    dailyQuestions: api.daily.map((d) => ({ day: d.day.slice(5), count: d.count })),
    vectorStore: [
      { label: "存储引擎", value: "PostgreSQL + pgvector" },
      { label: "向量模型", value: space?.model ?? "暂无向量（未配置 embedding 或尚未索引）" },
      { label: "向量维度", value: space ? String(space.dimension) : "—" },
      { label: "索引类型", value: "精确检索（无 ANN 索引）" },
      { label: "文档总数", value: String(api.documents) },
      { label: "向量总数", value: String(api.vectors) },
      { label: "最近同步", value: api.lastIndexedAt ? stamp(api.lastIndexedAt, zone) : "—" },
    ],
    recentDocuments: api.recentDocuments.map((d) => ({
      name: `${d.title} ${d.versionLabel}`, chunks: d.chunks || null, status: indexStatus(d.indexStatus), updatedAt: stamp(d.createdAt, zone),
    })),
    recentQuestions: api.recentQuestions.map((q) => ({
      question: q.question ?? "（他人提问，正文不可见）", citations: q.citationCount, askedAt: stamp(q.createdAt, zone),
    })),
  };
}
