package dev.zhiku.evaluation;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class EvaluationTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired IngestionWorker worker;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  UUID kb;
  UUID leave;
  UUID travel;

  static final String LEAVE = "# 年假\n\n年假按司龄计算：1-3 年 5 天，3-10 年 10 天，10 年以上 15 天。\n\n## 请假流程\n\n年假申请需提前 3 个工作日提交。";
  static final String TRAVEL = "# 差旅\n\n出差住宿按城市分级执行标准，超标部分需事前审批。";

  @BeforeEach
  void seed() throws Exception {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "评测-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, operator);
    leave = ingest("员工手册", LEAVE);
    travel = ingest("差旅标准", TRAVEL);
  }

  UUID ingest(String title, String text) throws Exception {
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", doc, kb, title, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())", version, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", "doc.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(as(req, operator, Role.KB_OPERATOR)).andExpect(status().isAccepted());
    assertThat(worker.processNext()).isTrue();
    return version;
  }

  <T extends MockHttpServletRequestBuilder> T as(T req, UUID user, Role... roles) {
    req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)));
    return req;
  }

  ResultActions createCase(String body) throws Exception {
    return mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-cases", kb), operator, Role.KB_OPERATOR).contentType("application/json").content(body));
  }

  static final String DETAILS = "\"expectedAnswer\":\"按原文作答\",\"requiredFacts\":[\"原文中的关键事实\"]";

  String caseJson(String question, UUID version, String quote) {
    return "{\"question\":\"" + question + "\",\"label\":\"answerable\"," + DETAILS + ",\"evidence\":[{\"versionId\":\"" + version + "\",\"quote\":\"" + quote + "\"}]}";
  }

  ResultActions details(UUID caseId, String body) throws Exception {
    return mvc.perform(as(put("/api/knowledge-bases/{kb}/eval-cases/{id}/details", kb, caseId), operator, Role.KB_OPERATOR)
        .contentType("application/json").content(body));
  }

  UUID id(ResultActions r) throws Exception {
    return UUID.fromString(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asText());
  }

  void approve(UUID caseId) throws Exception {
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-cases/{id}/approve", kb, caseId), operator, Role.KB_OPERATOR)).andExpect(status().isNoContent());
  }

  ResultActions run(int k) throws Exception {
    return mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb), operator, Role.KB_OPERATOR).contentType("application/json").content("{\"k\":" + k + "}"));
  }

  @Test
  void evidenceIsLocatedAsSourceSpansNotChunkIds() throws Exception {
    var caseId = id(createCase(caseJson("年假有几天?", leave, "10 年以上 15 天")).andExpect(status().isCreated()));
    var normalized = LEAVE;
    var start = normalized.indexOf("10 年以上 15 天");
    assertThat(jdbc.queryForObject("SELECT start_offset FROM eval_evidence WHERE case_id = ?", Integer.class, caseId)).isEqualTo(start);
    assertThat(jdbc.queryForObject("SELECT end_offset FROM eval_evidence WHERE case_id = ?", Integer.class, caseId)).isEqualTo(start + "10 年以上 15 天".length());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-cases", kb), operator, Role.KB_OPERATOR))
        .andExpect(jsonPath("$[0].status").value("candidate"))
        .andExpect(jsonPath("$[0].evidence[0].quote").value("10 年以上 15 天"))
        .andExpect(jsonPath("$[0].evidence[0].available").value(true));
  }

  @Test
  void evidenceQuotesAreHiddenOnceTheSourceIsWithdrawnOrDeleted() throws Exception {
    id(createCase(caseJson("年假有几天?", leave, "10 年以上 15 天")).andExpect(status().isCreated()));
    id(createCase(caseJson("住宿标准?", travel, "超标部分需事前审批")).andExpect(status().isCreated()));
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", leave);
    jdbc.update("UPDATE document SET deleted_at = now(), cleanup_status = 'pending' WHERE id = (SELECT document_id FROM content_version WHERE id = ?)", travel);
    var body = mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-cases", kb), operator, Role.KB_OPERATOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].evidence[0].available").value(false))
        .andExpect(jsonPath("$[1].evidence[0].available").value(false))
        .andExpect(jsonPath("$[0].evidence[0].startOffset").isNumber())
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("10 年以上 15 天").doesNotContain("超标部分需事前审批");
  }

  UUID messageIn(UUID knowledgeBase) {
    var conversation = UUID.randomUUID();
    var message = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, '年假')", conversation, enterprise, operator, knowledgeBase);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model) VALUES (?, ?, '年假有几天?', '答[1]', 'test')", message, conversation);
    jdbc.update("INSERT INTO feedback (message_id, user_id, rating, reason, version) VALUES (?, ?, 'down', 'WRONG', 2)", message, operator);
    return message;
  }

  @Test
  void casesCreatedFromFeedbackKeepTheirOriginWithinTheKnowledgeBase() throws Exception {
    var origin = messageIn(kb);
    createCase("{\"question\":\"年假有几天?\",\"label\":\"answerable\",\"source\":\"feedback\",\"originMessageId\":\"" + origin + "\"}")
        .andExpect(status().isCreated());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-cases", kb), operator, Role.KB_OPERATOR))
        .andExpect(jsonPath("$[0].originMessageId").value(origin.toString()));
    assertThat(jdbc.queryForObject("SELECT origin_feedback_version FROM eval_case WHERE origin_message_id = ?", Integer.class, origin)).isEqualTo(2);

    var otherKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", otherKb, enterprise, "其他-" + otherKb);
    createCase("{\"question\":\"x\",\"label\":\"answerable\",\"originMessageId\":\"" + messageIn(otherKb) + "\"}")
        .andExpect(status().isUnprocessableEntity());
    createCase("{\"question\":\"x\",\"label\":\"answerable\",\"originMessageId\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void nullEvidenceElementsAreRejectedAsInvalidInput() throws Exception {
    createCase("{\"question\":\"x\",\"label\":\"answerable\",\"evidence\":[null]}").andExpect(status().isUnprocessableEntity());
    createCase("{\"question\":\"x\",\"label\":\"answerable\",\"evidence\":[{\"versionId\":\"" + leave + "\",\"quote\":\"年假\"},null]}")
        .andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM eval_case WHERE kb_id = ?", Integer.class, kb)).isZero();
    var caseId = id(createCase(caseJson("年假有几天?", leave, "10 年以上 15 天")));
    mvc.perform(as(put("/api/knowledge-bases/{kb}/eval-cases/{id}/evidence", kb, caseId), operator, Role.KB_OPERATOR)
        .contentType("application/json").content("{\"evidence\":[null]}")).andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM eval_evidence WHERE case_id = ?", Integer.class, caseId)).isEqualTo(1);
  }

  @Test
  void aQuoteFoundMoreThanOnceNeedsItsOccurrence() throws Exception {
    var twice = ingest("请假时限", "甲部门：五个工作日。\n\n乙部门：五个工作日。");
    String ev = "{\"question\":\"乙部门几天?\",\"label\":\"answerable\",\"evidence\":[{\"versionId\":\"" + twice + "\",\"quote\":\"五个工作日\"%s}]}";
    createCase(ev.formatted("")).andExpect(status().isUnprocessableEntity());
    createCase(ev.formatted(",\"occurrence\":3")).andExpect(status().isUnprocessableEntity());
    var caseId = id(createCase(ev.formatted(",\"occurrence\":2")).andExpect(status().isCreated()));
    var second = "甲部门：五个工作日。\n\n乙部门：五个工作日。".lastIndexOf("五个工作日");
    assertThat(jdbc.queryForObject("SELECT start_offset FROM eval_evidence WHERE case_id = ?", Integer.class, caseId)).isEqualTo(second);
  }

  @Test
  void theSameSpanCannotBeCountedTwice() throws Exception {
    createCase("{\"question\":\"年假?\",\"label\":\"answerable\",\"evidence\":[{\"versionId\":\"" + leave + "\",\"quote\":\"10 年以上 15 天\"},"
        + "{\"versionId\":\"" + leave + "\",\"quote\":\"10 年以上 15 天\"}]}").andExpect(status().isUnprocessableEntity());
  }

  @Test
  void anEvidenceEditWaitsForAnApprovalInProgressAndThenFindsTheCaseFrozen() throws Exception {
    var caseId = id(createCase(caseJson("年假有几天?", leave, "10 年以上 15 天")));
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var approval = java.util.concurrent.CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
      jdbc.queryForList("SELECT 1 FROM eval_case WHERE id = ? FOR UPDATE", Integer.class, caseId);
      jdbc.update("UPDATE eval_case SET status = 'approved', approved_by = ? WHERE id = ?", operator, caseId);
      locked.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }));
    locked.await();
    java.util.concurrent.CompletableFuture<Integer> edit = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return mvc.perform(as(put("/api/knowledge-bases/{kb}/eval-cases/{id}/evidence", kb, caseId), operator, Role.KB_OPERATOR)
            .contentType("application/json").content("{\"evidence\":[{\"versionId\":\"" + leave + "\",\"quote\":\"1-3 年 5 天\"}]}"))
            .andReturn().getResponse().getStatus();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(300);
    assertThat(edit.isDone()).isFalse();
    release.countDown();
    approval.get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(edit.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT quote FROM eval_evidence WHERE case_id = ?", String.class, caseId)).isEqualTo("10 年以上 15 天");
  }

  @Test
  void aSpanCountsAsRetrievedOnlyWhenRetrievedChunksCoverAllOfIt() {
    assertThat(EvaluationService.covers(10, 20, List.of(new int[] {0, 11}))).isFalse();
    assertThat(EvaluationService.covers(10, 20, List.of(new int[] {0, 15}, new int[] {15, 30}))).isTrue();
    assertThat(EvaluationService.covers(10, 20, List.of(new int[] {0, 14}, new int[] {16, 30}))).isFalse();
    assertThat(EvaluationService.covers(10, 20, List.of(new int[] {12, 25}, new int[] {5, 13}, new int[] {5, 13}))).isTrue();
    assertThat(EvaluationService.covers(10, 20, List.of())).isFalse();
  }

  @Test
  void evidenceFromWithdrawnOrDeletedSourcesIsReportedSeparatelyBeforeAndAfterCleanup() throws Exception {
    approve(id(createCase(caseJson("年假有几天", leave, "10 年以上 15 天"))));
    approve(id(createCase(caseJson("住宿标准是什么", travel, "出差住宿按城市分级执行标准"))));
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", travel);
    run(5).andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.evaluated").value(1))
        .andExpect(jsonPath("$.summary.sourceUnavailable").value(1))
        .andExpect(jsonPath("$.summary.prepFailed").value(0))
        .andExpect(jsonPath("$.summary.hit.den").value(1))
        .andExpect(jsonPath("$.results[?(@.sourceUnavailable == true)].question").value(org.hamcrest.Matchers.contains("住宿标准是什么")));

    jdbc.update("UPDATE content_version SET withdrawn_at = NULL WHERE id = ?", travel);
    jdbc.update("UPDATE document SET deleted_at = now(), cleanup_status = 'pending' WHERE id = (SELECT document_id FROM content_version WHERE id = ?)", travel);
    run(5).andExpect(jsonPath("$.summary.sourceUnavailable").value(1)).andExpect(jsonPath("$.summary.prepFailed").value(0));
    jdbc.update("DELETE FROM chunk WHERE version_id = ?", travel);
    run(5).andExpect(jsonPath("$.summary.sourceUnavailable").value(1)).andExpect(jsonPath("$.summary.prepFailed").value(0));
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs/latest", kb), operator, Role.KB_OPERATOR))
        .andExpect(jsonPath("$.results[?(@.sourceUnavailable == true)].question").value(org.hamcrest.Matchers.contains("住宿标准是什么")));
  }

  @Test
  void rejectsEvidenceThatIsNotInTheVersion() throws Exception {
    createCase(caseJson("年假有几天?", leave, "原文里没有这句话")).andExpect(status().isUnprocessableEntity());
    createCase(caseJson("年假有几天?", UUID.randomUUID(), "年假")).andExpect(status().isUnprocessableEntity());
    createCase("{\"question\":\"x\",\"label\":\"maybe\",\"evidence\":[]}").andExpect(status().isUnprocessableEntity());
  }

  @Test
  void onlyApprovedCasesAreEvaluated() throws Exception {
    createCase(caseJson("年假有几天?", leave, "10 年以上 15 天")).andExpect(status().isCreated());
    run(5).andExpect(status().isOk()).andExpect(jsonPath("$.summary.evaluated").value(0));
  }

  @Test
  void retrievalRunReportsHitRecallAndPreparationFailures() throws Exception {
    approve(id(createCase(caseJson("年假有几天", leave, "10 年以上 15 天"))));
    approve(id(createCase(caseJson("住宿标准是什么", travel, "出差住宿按城市分级执行标准"))));
    var stale = id(createCase(caseJson("请假要提前几天", leave, "提前 3 个工作日")));
    approve(stale);
    jdbc.update("DELETE FROM chunk WHERE version_id = ? AND text LIKE '%提前 3 个工作日%' AND text NOT LIKE '%15 天%'", leave);
    jdbc.update("UPDATE eval_evidence SET start_offset = 100000, end_offset = 100010 WHERE case_id = ?", stale);

    run(5).andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.evaluated").value(2))
        .andExpect(jsonPath("$.summary.prepFailed").value(1))
        .andExpect(jsonPath("$.summary.hit.num").value(2))
        .andExpect(jsonPath("$.summary.hit.den").value(2))
        .andExpect(jsonPath("$.summary.recall.den").value(2))
        .andExpect(jsonPath("$.summary.p95Ms").isNumber())
        .andExpect(jsonPath("$.k").value(5));
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs/latest", kb), operator, Role.KB_OPERATOR))
        .andExpect(jsonPath("$.results.length()").value(3))
        .andExpect(jsonPath("$.results[?(@.mappingFailed == true)].question").value(org.hamcrest.Matchers.contains("请假要提前几天")));
  }

  @Test
  void feedbackCandidatesGetEvidenceBeforeApproval() throws Exception {
    var caseId = id(createCase("{\"question\":\"年假有几天?\",\"label\":\"answerable\",\"source\":\"feedback\"}").andExpect(status().isCreated()));
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-cases/{id}/approve", kb, caseId), operator, Role.KB_OPERATOR))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/knowledge-bases/{kb}/eval-cases/{id}/evidence", kb, caseId), operator, Role.KB_OPERATOR)
        .contentType("application/json").content("{\"evidence\":[{\"versionId\":\"" + leave + "\",\"quote\":\"10 年以上 15 天\"}]}"))
        .andExpect(status().isNoContent());
    details(caseId, "{" + DETAILS + "}").andExpect(status().isNoContent());
    approve(caseId);
    mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/knowledge-bases/{kb}/eval-cases/{id}/evidence", kb, caseId), operator, Role.KB_OPERATOR)
        .contentType("application/json").content("{\"evidence\":[{\"versionId\":\"" + leave + "\",\"quote\":\"年假\"}]}"))
        .andExpect(status().isConflict());
  }

  @Test
  void answerableCasesNeedAReferenceAnswerAndRequiredFactsBeforeApproval() throws Exception {
    var caseId = id(createCase("{\"question\":\"年假有几天?\",\"label\":\"answerable\",\"evidence\":[{\"versionId\":\"" + leave + "\",\"quote\":\"10 年以上 15 天\"}]}"));
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-cases/{id}/approve", kb, caseId), operator, Role.KB_OPERATOR))
        .andExpect(status().isUnprocessableEntity());
    details(caseId, "{\"expectedAnswer\":\"按司龄计算\",\"requiredFacts\":[\"\"]}").andExpect(status().isUnprocessableEntity());
    details(caseId, "{\"expectedAnswer\":\"按司龄计算\",\"requiredFacts\":[" + "\"事实\",".repeat(10) + "\"事实\"]}").andExpect(status().isUnprocessableEntity());
    details(caseId, "{\"expectedAnswer\":\"按司龄计算，10 年以上 15 天\",\"requiredFacts\":[\"10 年以上 15 天\",\"按司龄计算\"],"
        + "\"appliesTo\":\"正式员工\",\"queryAt\":\"2026-03-01T00:00:00Z\"}").andExpect(status().isNoContent());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-cases", kb), operator, Role.KB_OPERATOR))
        .andExpect(jsonPath("$[0].expectedAnswer").value("按司龄计算，10 年以上 15 天"))
        .andExpect(jsonPath("$[0].requiredFacts.length()").value(2))
        .andExpect(jsonPath("$[0].appliesTo").value("正式员工"))
        .andExpect(jsonPath("$[0].queryAt").value("2026-03-01T00:00:00Z"));
    approve(caseId);
    details(caseId, "{" + DETAILS + "}").andExpect(status().isConflict());
  }

  @Test
  void aCaseIsEvaluatedAsOfItsQueryTime() throws Exception {
    var future = UUID.randomUUID();
    var doc = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '新制度', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v2', '2099-01-01T00:00:00Z', ?, now(), now())", future, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, future)
        .file(new MockMultipartFile("file", "doc.md", "text/markdown", "# 新年假\n\n2099 年起年假统一为 20 天。".getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(as(req, operator, Role.KB_OPERATOR)).andExpect(status().isAccepted());
    assertThat(worker.processNext()).isTrue();

    var now = id(createCase(caseJson("2099 年年假几天", future, "年假统一为 20 天")));
    approve(now);
    var later = id(createCase(caseJson("2099 年年假几天?", future, "年假统一为 20 天")));
    details(later, "{" + DETAILS + ",\"queryAt\":\"2099-06-01T00:00:00Z\"}").andExpect(status().isNoContent());
    approve(later);
    run(5).andExpect(status().isOk())
        .andExpect(jsonPath("$.results[?(@.caseId == '" + now + "')].sourceUnavailable").value(org.hamcrest.Matchers.contains(true)))
        .andExpect(jsonPath("$.results[?(@.caseId == '" + later + "')].sourceUnavailable").value(org.hamcrest.Matchers.contains(false)))
        .andExpect(jsonPath("$.results[?(@.caseId == '" + later + "')].hit").value(org.hamcrest.Matchers.contains(true)));
  }

  @Test
  void requiresEvaluateRoleAndGrant() throws Exception {
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-cases", kb), operator, Role.EMPLOYEE)).andExpect(status().isForbidden());
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb), UUID.randomUUID(), Role.KB_OPERATOR)
        .contentType("application/json").content("{}")).andExpect(status().isForbidden());
  }

  @Test
  void latestRunIsEmptyBeforeAnyRun() throws Exception {
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs/latest", kb), operator, Role.KB_OPERATOR)).andExpect(status().isNoContent());
  }
}
