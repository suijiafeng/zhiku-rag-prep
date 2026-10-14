import type { IndexStatus } from "../../components/IndexStatusTag";

/** Neutral, fictional sample data for the scaffold. Not loaded from any service. */
export interface Chunk {
  seq: number;
  tokens: number;
  text: string;
}

export interface DocumentRow {
  id: string;
  name: string;
  size: string;
  chunkCount: number | null;
  vectorCount: number | null;
  status: IndexStatus;
  updatedAt: string;
  chunks: Chunk[];
}

export interface DocumentsData {
  documents: DocumentRow[];
}

export const documentsFixture: DocumentsData = {
  documents: [
    {
      id: "doc-1", name: "员工手册 v3.2.pdf", size: "2.4 MB", chunkCount: 128, vectorCount: 128,
      status: "indexed", updatedAt: "10-13 01:42",
      chunks: [
        { seq: 1, tokens: 486, text: "第一章 总则 本手册适用于公司全体员工，包含考勤、福利制度说明…" },
        { seq: 2, tokens: 512, text: "1.2 考勤制度 员工应于工作日 9:00 前到岗，迟到的处理方式见附表…" },
        { seq: 3, tokens: 498, text: "1.3 请假流程 年假申请需提前 3 个工作日提交，经直属上级审批…" },
      ],
    },
    {
      id: "doc-2", name: "报销流程说明.md", size: "18 KB", chunkCount: 24, vectorCount: 24,
      status: "indexed", updatedAt: "10-12 17:20",
      chunks: [{ seq: 1, tokens: 402, text: "报销申请需在费用发生后 30 天内提交，并附上发票…" }],
    },
    { id: "doc-3", name: "Q3 销售数据.xlsx", size: "860 KB", chunkCount: null, vectorCount: null, status: "parsing", updatedAt: "10-12 16:58", chunks: [] },
    {
      id: "doc-4", name: "IT 安全规范.docx", size: "1.1 MB", chunkCount: 96, vectorCount: 96,
      status: "indexed", updatedAt: "10-12 15:31",
      chunks: [{ seq: 1, tokens: 455, text: "所有办公设备须启用屏幕锁定，离开工位时应锁屏…" }],
    },
    { id: "doc-5", name: "2024 年报终版.pdf", size: "8.2 MB", chunkCount: null, vectorCount: null, status: "failed", updatedAt: "10-12 14:02", chunks: [] },
    {
      id: "doc-6", name: "考勤制度.txt", size: "6 KB", chunkCount: 12, vectorCount: 12,
      status: "indexed", updatedAt: "10-12 11:05",
      chunks: [{ seq: 1, tokens: 310, text: "考勤以门禁打卡记录为准，异常需当月内提交更正申请…" }],
    },
    {
      id: "doc-7", name: "新员工培训.pptx", size: "4.7 MB", chunkCount: 64, vectorCount: 64,
      status: "indexed", updatedAt: "10-11 18:44",
      chunks: [{ seq: 1, tokens: 280, text: "入职第一周：领取设备、开通账号、完成安全培训…" }],
    },
    {
      id: "doc-8", name: "差旅标准 v2.md", size: "22 KB", chunkCount: 18, vectorCount: 18,
      status: "indexed", updatedAt: "10-11 09:15",
      chunks: [{ seq: 1, tokens: 366, text: "出差住宿按城市分级执行标准，超标部分需事前审批…" }],
    },
  ],
};
