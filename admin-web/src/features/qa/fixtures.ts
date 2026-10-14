import type { ChatEvent, Citation } from "./chatState";

/** Neutral, fictional sample data for the scaffold. Not produced by any model or retriever. */
export interface Source extends Citation {
  chunk: string;
  similarity: number;
}

export interface QaData {
  sessions: { id: string; title: string; updatedAt: string }[];
  sample: { question: string; answer: string; citations: Citation[] };
  sources: Source[];
  quickQuestions: string[];
  replay: ChatEvent[];
}

const handbook = { documentId: "doc-1", versionId: "doc-1-v3.2", title: "员工手册 v3.2.pdf" };
const citations: Citation[] = [
  { ...handbook, excerpt: "1.2 考勤制度…年假按司龄计算：1-3 年 5 天，3-10 年 10 天，10 年以上 15 天…" },
  { ...handbook, excerpt: "1.3 请假流程…年假申请需提前 3 个工作日提交，经直属上级审批…" },
];
const answer = "员工年假按司龄计算：1-3 年 5 天，3-10 年 10 天，10 年以上 15 天[1]。申请需提前 3 个工作日提交，经直属上级审批[2]。";

export const qaFixture: QaData = {
  sessions: [
    { id: "s1", title: "年假与请假咨询", updatedAt: "10-13 02:12" },
    { id: "s2", title: "报销流程问题", updatedAt: "10-13 01:58" },
    { id: "s3", title: "VPN 申请", updatedAt: "10-13 00:26" },
    { id: "s4", title: "差旅标准", updatedAt: "10-12 23:51" },
    { id: "s5", title: "考勤制度", updatedAt: "10-12 11:05" },
  ],
  sample: { question: "年假最多可以休几天？需要提前多久申请？", answer, citations },
  sources: [
    { ...citations[0]!, chunk: "#002", similarity: 0.87 },
    { ...citations[1]!, chunk: "#003", similarity: 0.81 },
    { documentId: "doc-6", versionId: "doc-6-v1", title: "考勤制度.txt", excerpt: "年假未休完部分可结转至次年 3 月底，逾期作废…", chunk: "#004", similarity: 0.76 },
  ],
  quickQuestions: ["年假有几天?", "报销多久到账?", "试用期多久?"],
  replay: [
    ...answer.match(/.{1,12}/gsu)!.map((text): ChatEvent => ({ event: "draft", data: { text } })),
    { event: "validated", data: { citations, validation: "metadata-only" } },
    { event: "done", data: { status: "complete" } },
  ],
};
