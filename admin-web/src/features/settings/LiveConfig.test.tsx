import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { KnowledgeBaseConfig } from "../../api/client";
import { ConfigHistory, PublishedCard, candidateBody, publishBlockers, validationLabel } from "./LiveConfig";

const base: KnowledgeBaseConfig = {
  id: "c1", version: null, status: "candidate", revision: 3, retrievalK: 10, contextChunks: 4, handoffChannel: "人事服务台", promptAddendum: null,
  note: "调小入模证据", createdBy: "u1", createdAt: "2026-10-14T01:00:00Z", validatedAt: null, validation: null, publishedBy: null, publishedAt: null,
  rolledBackFrom: null,
};

describe("config candidate", () => {
  it("builds the request from the form and keeps the revision for the lock", () => {
    expect(candidateBody({ retrievalK: 10, contextChunks: 4, handoffChannel: " 人事服务台 ", promptAddendum: "", note: "" }, 3))
      .toEqual({ retrievalK: 10, contextChunks: 4, handoffChannel: "人事服务台", expectedRevision: 3 });
    expect(candidateBody({ retrievalK: 8, contextChunks: 5, handoffChannel: "", promptAddendum: "规则", note: "n" }, undefined))
      .toEqual({ retrievalK: 8, contextChunks: 5, promptAddendum: "规则", note: "n" });
  });

  it("says why a candidate cannot be published yet", () => {
    expect(publishBlockers(undefined)).toEqual(["尚无候选配置"]);
    expect(publishBlockers(base)).toEqual(["尚未验证"]);
    expect(publishBlockers({ ...base, validatedAt: "2026-10-14T02:00:00Z", validation: { evaluationRunId: "r", cases: 2, humanQualityChecked: false } })).toEqual([]);
  });

  it("describes a validation without claiming answer quality", () => {
    expect(validationLabel(null)).toBe("未验证");
    expect(validationLabel({ evaluationRunId: "r", cases: 0, humanQualityChecked: false })).toBe("已验证：检索可用，评测 0 个用例；人工质量未评测");
  });

  it("shows the published version with its predecessor and offers rollback only for older versions", () => {
    const v2: KnowledgeBaseConfig = { ...base, id: "c2", version: 2, status: "published", publishedAt: "2026-10-14T03:00:00Z", retrievalK: 12 };
    const v1: KnowledgeBaseConfig = { ...base, id: "c0", version: 1, status: "superseded", publishedAt: "2026-10-14T02:00:00Z" };
    const published = renderToStaticMarkup(<PublishedCard published={v2} history={[v2, v1]} defaults={{ retrievalK: 8, contextChunks: 5, handoffChannel: "", maxContextChunks: 10 }} />);
    expect(published).toContain("版本 2");
    expect(published).toContain("上个版本 1");
    expect(published).toContain("新问答请求立即使用");
    const history = renderToStaticMarkup(<ConfigHistory history={[v2, v1]} onRollback={() => {}} />);
    expect((history.match(/回滚到此版本/g) ?? []).length).toBe(1);
    const none = renderToStaticMarkup(<PublishedCard published={null} history={[]} defaults={{ retrievalK: 8, contextChunks: 5, handoffChannel: "", maxContextChunks: 10 }} />);
    expect(none).toContain("部署默认值");
  });
});
