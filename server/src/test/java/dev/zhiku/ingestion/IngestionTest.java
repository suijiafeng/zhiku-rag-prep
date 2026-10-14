package dev.zhiku.ingestion;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class IngestionTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired IngestionWorker worker;
  @Autowired dev.zhiku.support.TestEmbeddingProvider embeddings;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  UUID kb;
  UUID doc;
  UUID version;

  static final String HANDBOOK = "# 员工手册\n\n## 年假\n\n年假按司龄计算：1-3 年 5 天，3-10 年 10 天，10 年以上 15 天。\n\n## 请假流程\n\n申请需提前 3 个工作日提交，经直属上级审批。\n";

  @org.junit.jupiter.api.AfterEach
  void restore() {
    embeddings.failing.set(false);
  }

  int vectors() {
    return jdbc.queryForObject("SELECT count(*) FROM chunk_embedding e JOIN chunk c ON c.id = e.chunk_id WHERE c.version_id = ?", Integer.class, version);
  }

  void reindex() throws Exception {
    mvc.perform(post("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/reindex", kb, doc, version)
        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)))).andExpect(status().isAccepted());
  }

  @BeforeEach
  void seed() {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    doc = UUID.randomUUID();
    version = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "摄取-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, operator);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", doc, kb, "员工手册", operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v1', now(), ?)", version, doc, operator);
  }

  ResultActions upload(String name, byte[] bytes, UUID user, Role... roles) throws Exception {
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
    req.with(r -> { r.setMethod("PUT"); return r; });
    return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles))));
  }

  ResultActions upload(String name, String text) throws Exception {
    return upload(name, text.getBytes(StandardCharsets.UTF_8), operator, Role.KB_OPERATOR);
  }

  String jobStatus() {
    return jdbc.queryForObject("SELECT status FROM ingestion_job WHERE version_id = ?", String.class, version);
  }

  String indexStatus() {
    return jdbc.queryForObject("SELECT index_status FROM content_version WHERE id = ?", String.class, version);
  }

  int chunks() {
    return jdbc.queryForObject("SELECT count(*) FROM chunk WHERE version_id = ?", Integer.class, version);
  }

  @Test
  void uploadPersistsTheJobBeforeAnyProcessing() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").isString());
    assertThat(jobStatus()).isEqualTo("queued");
    assertThat(indexStatus()).isEqualTo("pending");
    assertThat(chunks()).isZero();
    assertThat(jdbc.queryForObject("SELECT sha256 FROM content_blob WHERE version_id = ?", String.class, version)).hasSize(64);

    assertThat(worker.processNext()).isTrue();
    assertThat(jobStatus()).isEqualTo("succeeded");
    assertThat(indexStatus()).isEqualTo("ready");
    assertThat(chunks()).isGreaterThan(0);
    assertThat(jdbc.queryForObject("SELECT heading FROM chunk WHERE version_id = ? ORDER BY seq LIMIT 1", String.class, version)).isEqualTo("员工手册");
    assertThat(worker.processNext()).isFalse();
  }

  @Test
  void rejectsUnsafeOrUnsupportedFiles() throws Exception {
    upload("empty.md", "").andExpect(status().isUnprocessableEntity());
    upload("blank.txt", " \n\t\n").andExpect(status().isUnprocessableEntity());
    upload("report.pdf", "%PDF-1.7").andExpect(status().isUnprocessableEntity());
    upload("noext", HANDBOOK).andExpect(status().isUnprocessableEntity());
    upload("binary.txt", new byte[] {'a', 0, 'b'}, operator, Role.KB_OPERATOR).andExpect(status().isUnprocessableEntity());
    upload("latin1.txt", new byte[] {(byte) 0xC3, (byte) 0x28}, operator, Role.KB_OPERATOR).andExpect(status().isUnprocessableEntity());
    upload("huge.txt", new byte[20 * 1024 * 1024 + 1], operator, Role.KB_OPERATOR).andExpect(status().isPayloadTooLarge());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_job WHERE version_id = ?", Integer.class, version)).isZero();
  }

  @Test
  void contentIsImmutableOnceUploaded() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    upload("handbook.md", HANDBOOK + "改").andExpect(status().isConflict());
  }

  @Test
  void requiresManageRoleAndGrant() throws Exception {
    upload("a.md", HANDBOOK.getBytes(StandardCharsets.UTF_8), operator, Role.EMPLOYEE).andExpect(status().isForbidden());
    upload("a.md", HANDBOOK.getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), Role.KB_OPERATOR).andExpect(status().isForbidden());
  }

  @Test
  void expiredLeaseIsRecoveredAfterACrash() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    jdbc.update("UPDATE ingestion_job SET status = 'running', lease_owner = 'crashed-worker', lease_until = ?, attempts = 1 WHERE version_id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), version);
    assertThat(worker.processNext()).isTrue();
    assertThat(jobStatus()).isEqualTo("succeeded");
    assertThat(jdbc.queryForObject("SELECT attempts FROM ingestion_job WHERE version_id = ?", Integer.class, version)).isEqualTo(2);
  }

  @Test
  void activeLeaseIsNotStolen() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    jdbc.update("UPDATE ingestion_job SET status = 'running', lease_owner = 'other', lease_until = ? WHERE version_id = ?",
        Timestamp.from(Instant.now().plusSeconds(300)), version);
    assertThat(worker.processNext()).isFalse();
  }

  @Test
  void reprocessingDoesNotDuplicateChunks() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    worker.processNext();
    int first = chunks();
    mvc.perform(post("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/reindex", kb, doc, version)
        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)))).andExpect(status().isAccepted());
    assertThat(jobStatus()).isEqualTo("queued");
    worker.processNext();
    assertThat(chunks()).isEqualTo(first);
    assertThat(jdbc.queryForObject("SELECT count(DISTINCT seq) FROM chunk WHERE version_id = ?", Integer.class, version)).isEqualTo(first);
  }

  @Test
  void lostLeaseDiscardsTheResult() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    var claimed = worker.claim().orElseThrow();
    jdbc.update("UPDATE ingestion_job SET lease_owner = 'thief' WHERE id = ?", claimed.jobId());
    worker.process(claimed);
    assertThat(chunks()).isZero();
    assertThat(jobStatus()).isEqualTo("running");
  }

  @Test
  void aReadyVersionStaysSearchableWhileItIsRebuiltAndWhenTheRebuildFails() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    worker.processNext();
    int chunks = chunks();
    int vectors = vectors();
    reindex();
    assertThat(indexStatus()).isEqualTo("ready");
    var claimed = worker.claim().orElseThrow();
    assertThat(indexStatus()).isEqualTo("ready");
    embeddings.failing.set(true);
    worker.process(claimed);
    assertThat(indexStatus()).isEqualTo("ready");
    assertThat(chunks()).isEqualTo(chunks);
    assertThat(vectors()).isEqualTo(vectors);
    jdbc.update("UPDATE ingestion_job SET status = 'running', lease_owner = 'x', lease_until = now() - interval '1 minute', attempts = 3 WHERE version_id = ?", version);
    worker.processNext();
    assertThat(jobStatus()).isEqualTo("failed");
    assertThat(indexStatus()).isEqualTo("ready");
    assertThat(jdbc.queryForObject("SELECT index_error FROM content_version WHERE id = ?", String.class, version)).contains("原索引");
  }

  @Test
  void aFailedAttemptWaitsBeforeItIsRetried() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    embeddings.failing.set(true);
    assertThat(worker.processNext()).isTrue();
    assertThat(jobStatus()).isEqualTo("queued");
    assertThat(worker.processNext()).isFalse();
    jdbc.update("UPDATE ingestion_job SET next_attempt_at = now() - interval '1 second' WHERE version_id = ?", version);
    embeddings.failing.set(false);
    assertThat(worker.processNext()).isTrue();
    assertThat(jobStatus()).isEqualTo("succeeded");
  }

  @Test
  void aStaleWorkersFailureDoesNotTouchTheVersion() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    var stale = worker.claim().orElseThrow();
    jdbc.update("UPDATE ingestion_job SET lease_owner = 'other' WHERE id = ?", stale.jobId());
    jdbc.update("UPDATE content_version SET index_status = 'ready' WHERE id = ?", version);
    embeddings.failing.set(true);
    worker.process(stale);
    assertThat(indexStatus()).isEqualTo("ready");
    assertThat(jobStatus()).isEqualTo("running");
  }

  @Test
  void exhaustedAttemptsMarkJobAndVersionFailed() throws Exception {
    upload("handbook.md", HANDBOOK).andExpect(status().isAccepted());
    jdbc.update("UPDATE ingestion_job SET status = 'running', lease_owner = 'x', lease_until = ?, attempts = 3 WHERE version_id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), version);
    worker.processNext();
    assertThat(jobStatus()).isEqualTo("failed");
    assertThat(indexStatus()).isEqualTo("failed");
    assertThat(jdbc.queryForObject("SELECT index_error FROM content_version WHERE id = ?", String.class, version)).isNotBlank();
  }
}
