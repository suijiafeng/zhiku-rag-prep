package dev.zhiku.retrieval;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestEmbeddingProvider;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class RetrievalTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired IngestionWorker worker;
  @Autowired TestEmbeddingProvider embeddings;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  UUID kb;

  static final String LEAVE = "# 年假\n\n年假按司龄计算：1-3 年 5 天，3-10 年 10 天，10 年以上 15 天。年假申请需提前 3 个工作日提交。";
  static final String TRAVEL = "# 差旅\n\n出差住宿按城市分级执行标准，超标部分需事前审批。交通费凭票报销。";

  @BeforeEach
  void seed() {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = newKb(operator);
  }

  @AfterEach
  void healthy() {
    embeddings.failing.set(false);
  }

  UUID newKb(UUID member) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", id, enterprise, "检索-" + id);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", id, member);
    return id;
  }

  /** 创建一个在 [from, until) 内生效的已发布版本，并导入其内容。 */
  UUID ingest(UUID kbId, String title, String text, String from, String until, boolean publish) throws Exception {
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", doc, kbId, title, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, effective_until, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v', CAST(? AS timestamptz), CAST(? AS timestamptz), ?, CASE WHEN ? THEN now() END, CASE WHEN ? THEN now() END)",
        version, doc, from, until, operator, publish, publish);
    upload(kbId, doc, version, text);
    assertThat(worker.processNext()).isTrue();
    return version;
  }

  void upload(UUID kbId, UUID doc, UUID version, String text) throws Exception {
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kbId, doc, version)
        .file(new MockMultipartFile("file", "doc.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)))).andExpect(status().isAccepted());
  }

  ResultActions search(UUID kbId, String body, UUID user, Role... roles) throws Exception {
    return mvc.perform(post("/api/knowledge-bases/{kb}/retrieval-debug", kbId)
        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)))
        .contentType("application/json").content(body));
  }

  List<String> versionsOf(ResultActions result) throws Exception {
    JsonNode root = json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    var out = new ArrayList<String>();
    root.get("candidates").forEach(c -> out.add(c.get("versionId").asText()));
    return out;
  }

  @Test
  void embedsChunksWithModelAndDimension() throws Exception {
    var v = ingest(kb, "员工手册", LEAVE, "2025-01-01T00:00:00Z", null, true);
    assertThat(jdbc.queryForObject("SELECT index_status FROM content_version WHERE id = ?", String.class, v)).isEqualTo("ready");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM chunk_embedding e JOIN chunk c ON c.id = e.chunk_id WHERE c.version_id = ? AND e.model = ? AND vector_dims(e.embedding) = ?",
        Integer.class, v, embeddings.model(), TestEmbeddingProvider.DIMENSION)).isPositive();
  }

  @Test
  void ranksRelevantChunksFirstAndReportsSkippedStages() throws Exception {
    var leave = ingest(kb, "员工手册", LEAVE, "2025-01-01T00:00:00Z", null, true);
    ingest(kb, "差旅标准", TRAVEL, "2025-01-01T00:00:00Z", null, true);
    var result = search(kb, "{\"query\":\"年假有几天\"}", operator, Role.KB_OPERATOR)
        .andExpect(jsonPath("$.stages[0].name").value("查询改写")).andExpect(jsonPath("$.stages[0].status").value("skipped"))
        .andExpect(jsonPath("$.stages[1].status").value("success"))
        .andExpect(jsonPath("$.stages[2].status").value("skipped"))
        .andExpect(jsonPath("$.stages[3].status").value("skipped"))
        .andExpect(jsonPath("$.stages[4].status").value("skipped"))
        .andExpect(jsonPath("$.embeddingModel").value(embeddings.model()))
        .andExpect(jsonPath("$.candidates[0].rank").value(1));
    assertThat(versionsOf(result).getFirst()).isEqualTo(leave.toString());
  }

  @Test
  void neverReturnsChunksFromOtherKnowledgeBases() throws Exception {
    var mine = ingest(kb, "员工手册", LEAVE, "2025-01-01T00:00:00Z", null, true);
    var other = newKb(operator); // 同一用户可读取两个知识库；检索仍限定在所请求的那个
    ingest(other, "相同内容", LEAVE, "2025-01-01T00:00:00Z", null, true);
    assertThat(versionsOf(search(kb, "{\"query\":\"年假\",\"k\":30}", operator, Role.KB_OPERATOR))).containsOnly(mine.toString());
  }

  @Test
  void onlyCurrentlyEffectivePublishedVersionsAreSearched() throws Exception {
    var current = ingest(kb, "制度A", LEAVE, "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", true);
    var future = ingest(kb, "制度B", LEAVE, "2099-01-01T00:00:00Z", null, true);
    var draft = ingest(kb, "草稿", LEAVE, "2025-01-01T00:00:00Z", null, false);
    var at2025 = versionsOf(search(kb, "{\"query\":\"年假\",\"k\":30,\"at\":\"2025-06-01T00:00:00Z\"}", operator, Role.KB_OPERATOR));
    assertThat(at2025).contains(current.toString()).doesNotContain(future.toString(), draft.toString());
    var at2099 = versionsOf(search(kb, "{\"query\":\"年假\",\"k\":30,\"at\":\"2099-06-01T00:00:00Z\"}", operator, Role.KB_OPERATOR));
    assertThat(at2099).contains(future.toString()).doesNotContain(current.toString());
  }

  @Test
  void requiresGrantAndDebugRole() throws Exception {
    ingest(kb, "员工手册", LEAVE, "2025-01-01T00:00:00Z", null, true);
    search(kb, "{\"query\":\"年假\"}", operator, Role.EMPLOYEE).andExpect(status().isForbidden());
    search(kb, "{\"query\":\"年假\"}", UUID.randomUUID(), Role.KB_OPERATOR).andExpect(status().isForbidden());
  }

  @Test
  void validatesQueryAndK() throws Exception {
    search(kb, "{\"query\":\"\"}", operator, Role.KB_OPERATOR).andExpect(status().isUnprocessableEntity());
    search(kb, "{\"query\":\"x\",\"k\":0}", operator, Role.KB_OPERATOR).andExpect(status().isUnprocessableEntity());
    search(kb, "{\"query\":\"x\",\"k\":31}", operator, Role.KB_OPERATOR).andExpect(status().isUnprocessableEntity());
  }

  @Test
  void embeddingOutageReportsDependencyUnavailable() throws Exception {
    ingest(kb, "员工手册", LEAVE, "2025-01-01T00:00:00Z", null, true);
    embeddings.failing.set(true);
    search(kb, "{\"query\":\"年假\"}", operator, Role.KB_OPERATOR)
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
  }

  @Test
  void embeddingOutageDuringIngestionIsRetriedNotMarkedReady() throws Exception {
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, 't', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v', now(), ?)", version, doc, operator);
    upload(kb, doc, version, LEAVE);
    embeddings.failing.set(true);
    worker.processNext();
    assertThat(jdbc.queryForObject("SELECT status FROM ingestion_job WHERE version_id = ?", String.class, version)).isEqualTo("queued");
    assertThat(jdbc.queryForObject("SELECT index_status FROM content_version WHERE id = ?", String.class, version)).isEqualTo("pending");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk WHERE version_id = ?", Integer.class, version)).isZero();
    embeddings.failing.set(false);
    assertThat(worker.processNext()).isFalse();
    jdbc.update("UPDATE ingestion_job SET next_attempt_at = now() WHERE version_id = ?", version);
    assertThat(worker.processNext()).isTrue();
    assertThat(jdbc.queryForObject("SELECT index_status FROM content_version WHERE id = ?", String.class, version)).isEqualTo("ready");
  }
}
