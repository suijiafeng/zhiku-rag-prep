/** 脚手架用的中性、虚构示例数据。不从任何服务加载。 */
import type { IndexStatus } from "../../components/IndexStatusTag";

export interface OverviewData {
  updatedAt: string;
  metrics: { title: string; value: string; delta: string; trend: "up" | "down" | "none" }[];
  dailyQuestions: { day: string; count: number }[];
  vectorStore: { label: string; value: string }[];
  recentDocuments: { name: string; chunks: number | null; status: IndexStatus; updatedAt: string }[];
  recentQuestions: { question: string; citations: number; askedAt: string }[];
}

export const overviewFixture: OverviewData = {
  updatedAt: "10-13 02:00",
  metrics: [
    { title: "知识文档总数", value: "1,284", delta: "12 今日新增", trend: "up" },
    { title: "索引向量", value: "86,420", delta: "3,102 今日新增", trend: "up" },
    { title: "今日问答", value: "3,842", delta: "18% 较昨日", trend: "up" },
    { title: "回答率@5", value: "92.4%", delta: "0.3% 较昨日", trend: "down" },
  ],
  dailyQuestions: [
    { day: "10-07", count: 1820 },
    { day: "10-08", count: 2105 },
    { day: "10-09", count: 1980 },
    { day: "10-10", count: 2458 },
    { day: "10-11", count: 2890 },
    { day: "10-12", count: 3120 },
    { day: "10-13", count: 3842 },
  ],
  vectorStore: [
    { label: "存储引擎", value: "PostgreSQL + pgvector" },
    { label: "向量维度", value: "待确认（ADR-002）" },
    { label: "索引类型", value: "待确认（先精确检索）" },
    { label: "文档总数", value: "1,284" },
    { label: "向量总数", value: "86,420" },
    { label: "最近同步", value: "2 分钟前" },
  ],
  recentDocuments: [
    { name: "员工手册 v3.2.pdf", chunks: 128, status: "indexed", updatedAt: "10-13 01:42" },
    { name: "报销流程说明.md", chunks: 24, status: "indexed", updatedAt: "10-13 17:30" },
    { name: "Q3 销售数据.xlsx", chunks: null, status: "parsing", updatedAt: "10-12 16:58" },
    { name: "IT 安全规范.docx", chunks: 96, status: "indexed", updatedAt: "10-12 15:21" },
    { name: "考勤制度.txt", chunks: 12, status: "indexed", updatedAt: "10-12 11:05" },
  ],
  recentQuestions: [
    { question: "年假最多可以休几天?", citations: 3, askedAt: "10-13 02:12" },
    { question: "报销审批需要几天?", citations: 2, askedAt: "10-13 01:58" },
    { question: "试用期转正流程是什么?", citations: 4, askedAt: "10-13 01:40" },
    { question: "VPN 怎么申请?", citations: 2, askedAt: "10-13 00:26" },
    { question: "加班餐补标准?", citations: 3, askedAt: "10-12 23:51" },
  ],
};
