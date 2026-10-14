package dev.zhiku.evaluation;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
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

  String caseJson(String question, UUID version, String quote) {
    return "{\"question\":\"" + question + "\",\"label\":\"answerable\",\"evidence\":[{\"versionId\":\"" + version + "\",\"quote\":\"" + quote + "\"}]}";
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
        .andExpect(jsonPath("$[0].evidence[0].quote").value("10 年以上 15 天"));
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
