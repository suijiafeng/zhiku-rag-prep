/** 中性、虚构的试点示例数据，并非任何评测运行的结果。 */

/** PRD v0.3 第 11 章的根因分类，按排查顺序排列。“模型问题”不是一个分类。 */
export const ROOT_CAUSES = [
  "文档未就绪或版本错误",
  "解析/切块遗漏",
  "授权范围不符合预期",
  "标注证据未召回",
  "融合或重排误排",
  "上下文预算挤出",
  "生成没有遵循证据",
  "引用映射失败",
] as const;
export type RootCause = (typeof ROOT_CAUSES)[number];

export interface EvalRow {
  id: string;
  question: string;
  /** 来源文档版本及位置；真值与切块 id 无关。 */
  expectedEvidence: string;
  evidenceHit: number;
  evidenceTotal: number;
  citationSupported: boolean | null;
  verdict: "correct" | "incorrect" | "pending";
  latencyMs: number;
  mappingFailed?: boolean;
  rootCause?: RootCause;
  note?: string;
}

export interface Candidate {
  question: string;
  source: string;
}

export interface EvaluationData {
  dataset: string;
  lastRun: string;
  rows: EvalRow[];
  candidates: Candidate[];
  proposal: { title: string; items: string[]; impact: string };
}

export const evaluationFixture: EvaluationData = {
  dataset: "示例评测集 sample-v1",
  lastRun: "10-13 02:00",
  rows: [
    { id: "q1", question: "年假最多可以休几天?", expectedEvidence: "员工手册 v3.2 · 1.2 节", evidenceHit: 3, evidenceTotal: 3, citationSupported: true, verdict: "correct", latencyMs: 1820 },
    { id: "q2", question: "报销审批需要几天?", expectedEvidence: "报销流程说明 v2 · 第 2 节", evidenceHit: 2, evidenceTotal: 2, citationSupported: true, verdict: "correct", latencyMs: 1650 },
    { id: "q3", question: "试用期离职流程是什么?", expectedEvidence: "员工手册 v3.2 · 4.1 节", evidenceHit: 2, evidenceTotal: 3, citationSupported: true, verdict: "correct", latencyMs: 2140 },
    {
      id: "q4", question: "Q3 华东区销售额?", expectedEvidence: "Q3 销售数据.xlsx · 表 1 第 4 行", evidenceHit: 0, evidenceTotal: 2,
      citationSupported: null, verdict: "incorrect", latencyMs: 2310, rootCause: "解析/切块遗漏", note: "表格切块打散，表头与数据行分离",
    },
    { id: "q5", question: "VPN 怎么申请?", expectedEvidence: "IT 安全规范 v1 · 3.2 节", evidenceHit: 2, evidenceTotal: 2, citationSupported: true, verdict: "correct", latencyMs: 1540 },
    {
      id: "q6", question: "加班餐补标准?", expectedEvidence: "考勤制度 v1 · 第 5 条", evidenceHit: 3, evidenceTotal: 3,
      citationSupported: false, verdict: "correct", latencyMs: 1980, rootCause: "引用映射失败", note: "结论正确，但引用指向差旅标准",
    },
    {
      id: "q7", question: "maternity 假几天?", expectedEvidence: "员工手册 v3.2 · 1.4 节", evidenceHit: 1, evidenceTotal: 2,
      citationSupported: true, verdict: "incorrect", latencyMs: 2050, rootCause: "标注证据未召回", note: "中英混合查询召回弱，漏掉例外条件",
    },
    { id: "q8", question: "差旅住宿上限?", expectedEvidence: "差旅标准 v2 · 第 3 条", evidenceHit: 2, evidenceTotal: 2, citationSupported: true, verdict: "correct", latencyMs: 1720 },
    {
      id: "q9", question: "门禁卡补办流程?", expectedEvidence: "行政指引 v1 · 第 7 条", evidenceHit: 0, evidenceTotal: 1,
      citationSupported: null, verdict: "pending", latencyMs: 0, mappingFailed: true, note: "来源位置无法映射到本次索引",
    },
  ],
  candidates: [
    { question: "Q3 华东区销售额?", source: "问答记录中的用户点踩 · 没答到" },
    { question: "VPN 密码重置?", source: "问答记录中的用户点踩 · 内容过期" },
  ],
  proposal: {
    title: "候选配置：表格按行并拼接表头切块",
    items: ["影响范围：3 个表格类文档需重建索引", "需用同一真值回归后才可发布；不改变业务审批与效力"],
    impact: "预期影响待评测验证，不预估修复数",
  },
};
