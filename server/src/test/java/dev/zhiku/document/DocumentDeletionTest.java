package dev.zhiku.document;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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

class DocumentDeletionTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired IngestionWorker worker;
  @Autowired DocumentCleanupWorker cleanup;
  @Autowired TestChatModel model;
  @Autowired DocumentRepository documents;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired dev.zhiku.support.TestEmbeddingProvider embeddings;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  final UUID employee = UUID.randomUUID();
  UUID kb;
  UUID doc;
  UUID version;

  static final String LEAVE = "# 年假\n\n年假按司龄计算：1-3 年 5 天，3-10 年 10 天，10 年以上 15 天。";

  @BeforeEach
  void seed() throws Exception {
    model.reset();
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    jdbc.update("UPDATE document SET cleanup_status = 'done' WHERE cleanup_status IN ('pending', 'running')");
    kb = UUID.randomUUID();
    doc = UUID.randomUUID();
    version = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "删除-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, operator, kb, employee);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '员工手册', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())", version, doc, operator);
    upload(version).andExpect(status().isAccepted());
    assertThat(worker.processNext()).isTrue();
  }

  @AfterEach
  void reset() {
    model.reset();
    embeddings.beforeEmbed = () -> {};
  }

  String token(UUID user, Role... roles) {
    return bearer(TestJwt.token(enterprise, user, roles));
  }

  ResultActions upload(UUID versionId) throws Exception {
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, versionId)
        .file(new MockMultipartFile("file", "handbook.md", "text/markdown", LEAVE.getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)));
  }

  ResultActions deleteDocument(String auth, UUID knowledgeBase, UUID document) throws Exception {
    return mvc.perform(delete("/api/knowledge-bases/{kb}/documents/{doc}", knowledgeBase, document).header(HttpHeaders.AUTHORIZATION, auth));
  }

  ResultActions deleteDocument() throws Exception {
    return deleteDocument(token(operator, Role.KB_OPERATOR), kb, doc);
  }

  String ask() throws Exception {
    var started = mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE))
        .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"年假有几天?\"}"))
        .andExpect(request().asyncStarted()).andReturn();
    started.getAsyncResult(10_000);
    return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class, version);
  }

  String cleanupStatus() {
    return jdbc.queryForObject("SELECT cleanup_status FROM document WHERE id = ?", String.class, doc);
  }

  @Test
  void deletionStopsRetrievalHistoryAndUploadsBeforeCleanupRuns() throws Exception {
    var done = ask();
    var conversation = done.substring(done.indexOf("\"conversationId\":\"") + 18).substring(0, 36);

    deleteDocument().andExpect(status().isAccepted());

    assertThat(count("SELECT count(*) FROM chunk WHERE version_id = ?")).isGreaterThan(0);
    mvc.perform(get("/api/knowledge-bases/{kb}/eligibility", kb).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(post("/api/knowledge-bases/{kb}/retrieval-debug", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))
            .contentType("application/json").content("{\"query\":\"年假有几天?\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[0].hidden").value(true))
        .andExpect(jsonPath("$[0].answer").doesNotExist());
    model.script = m -> { throw new AssertionError("deleted sources must not reach the model"); };
    assertThat(ask()).contains("NO_EVIDENCE").doesNotContain("event:validated");

    var next = UUID.randomUUID();
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v2', now(), ?)", next, doc, operator);
    upload(next).andExpect(status().isForbidden());
    mvc.perform(post("/api/knowledge-bases/{kb}/documents/{doc}/versions", kb, doc).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))
            .contentType("application/json").content("{\"versionLabel\":\"v3\",\"effectiveFrom\":\"2026-01-01\"}"))
        .andExpect(status().isForbidden());

    mvc.perform(get("/api/knowledge-bases/{kb}/documents", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$[0].id").value(doc.toString()))
        .andExpect(jsonPath("$[0].deletion.status").value("pending"))
        .andExpect(jsonPath("$[0].versions.length()").value(0));
    mvc.perform(get("/api/knowledge-bases/{kb}/overview", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$.documents").value(0))
        .andExpect(jsonPath("$.chunks").value(0))
        .andExpect(jsonPath("$.recentDocuments.length()").value(0));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'DOCUMENT_DELETE' AND target = ?", Integer.class, "document:" + doc))
        .isEqualTo(1);
  }

  @Test
  void deletionWhileARetrievalIsInFlightReturnsNoDeletedText() throws Exception {
    embeddings.beforeEmbed = () -> jdbc.update("UPDATE document SET deleted_at = now(), cleanup_status = 'pending' WHERE id = ?", doc);
    mvc.perform(post("/api/knowledge-bases/{kb}/retrieval-debug", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))
            .contentType("application/json").content("{\"query\":\"年假有几天?\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
  }

  @Test
  void revocationWhileARetrievalIsInFlightReturnsNothing() throws Exception {
    embeddings.beforeEmbed = () -> jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, operator);
    mvc.perform(post("/api/knowledge-bases/{kb}/retrieval-debug", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))
            .contentType("application/json").content("{\"query\":\"年假有几天?\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
  }

  @Test
  void aDeletionWaitsForAnUploadThatAlreadyPassedItsChecksAndCleanupThenRemovesIt() throws Exception {
    var next = UUID.randomUUID();
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v2', now(), ?)", next, doc, operator);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CompletableFuture<Boolean> upload = java.util.concurrent.CompletableFuture.supplyAsync(() -> tx.<Boolean>execute(status -> {
      if (!documents.lockLiveDocument(doc)) return false;
      locked.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      jdbc.update("INSERT INTO content_blob (version_id, media_type, file_name, byte_size, sha256, content, uploaded_by) "
          + "VALUES (?, 'text/plain', 'late.txt', 1, repeat('a', 64), 'x', ?)", next, operator);
      return true;
    }));
    locked.await();
    java.util.concurrent.CompletableFuture<Void> deletion = java.util.concurrent.CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> documents.markDeleted(doc, operator)));
    Thread.sleep(200);
    assertThat(deletion.isDone()).isFalse();
    release.countDown();
    boolean uploaded = upload.get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(uploaded).isTrue();
    deletion.get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(cleanup.processNext()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM content_blob WHERE version_id = ?", Integer.class, next)).isZero();
    Boolean live = tx.execute(status -> documents.lockLiveDocument(doc));
    assertThat(live).isFalse();
  }

  @Test
  void cleanupRemovesContentChunksVectorsAndJobs() throws Exception {
    deleteDocument().andExpect(status().isAccepted());
    assertThat(cleanup.processNext()).isTrue();

    assertThat(cleanupStatus()).isEqualTo("done");
    assertThat(count("SELECT count(*) FROM content_blob WHERE version_id = ?")).isZero();
    assertThat(count("SELECT count(*) FROM chunk WHERE version_id = ?")).isZero();
    assertThat(count("SELECT count(*) FROM ingestion_job WHERE version_id = ?")).isZero();
    assertThat(count("SELECT count(*) FROM chunk_embedding e JOIN chunk c ON c.id = e.chunk_id WHERE c.version_id = ?")).isZero();
    assertThat(cleanup.processNext()).isFalse();
    mvc.perform(get("/api/knowledge-bases/{kb}/documents", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$[0].deletion.status").value("done"));
  }

  @Test
  void deletedDocumentsAreNotIngested() throws Exception {
    var next = UUID.randomUUID();
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v2', now(), ?)", next, doc, operator);
    upload(next).andExpect(status().isAccepted());
    deleteDocument().andExpect(status().isAccepted());
    assertThat(worker.processNext()).isFalse();
  }

  @Test
  void requiresManageRoleGrantAndSameKnowledgeBase() throws Exception {
    deleteDocument(token(employee, Role.EMPLOYEE), kb, doc).andExpect(status().isForbidden());
    deleteDocument(token(UUID.randomUUID(), Role.KB_OPERATOR), kb, doc).andExpect(status().isForbidden());
    deleteDocument(bearer(TestJwt.token(UUID.randomUUID(), operator, Role.KB_OPERATOR)), kb, doc).andExpect(status().isForbidden());
    deleteDocument(token(operator, Role.KB_OPERATOR), kb, UUID.randomUUID()).andExpect(status().isForbidden());
    var otherKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", otherKb, enterprise, "其他-" + otherKb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", otherKb, operator);
    deleteDocument(token(operator, Role.KB_OPERATOR), otherKb, doc).andExpect(status().isForbidden());
    mvc.perform(delete("/api/knowledge-bases/{kb}/documents/{doc}", kb, doc)).andExpect(status().isUnauthorized());
    assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM document WHERE id = ?", Boolean.class, doc)).isTrue();
  }

  @Test
  void expiredCleanupLeaseIsRecovered() throws Exception {
    deleteDocument().andExpect(status().isAccepted());
    jdbc.update("UPDATE document SET cleanup_status = 'running', cleanup_lease_until = ?, cleanup_attempts = 1 WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), doc);
    assertThat(cleanup.processNext()).isTrue();
    assertThat(cleanupStatus()).isEqualTo("done");
    assertThat(count("SELECT count(*) FROM chunk WHERE version_id = ?")).isZero();
  }

  @Test
  void aStaleCleanupClaimCannotChangeTheStateOfTheNewClaim() throws Exception {
    deleteDocument().andExpect(status().isAccepted());
    var stale = cleanup.claim().orElseThrow();
    jdbc.update("UPDATE document SET cleanup_lease_until = now() - interval '1 minute' WHERE id = ?", doc);
    var current = cleanup.claim().orElseThrow();
    assertThat(current.token()).isNotEqualTo(stale.token());
    assertThat(current.attempts()).isEqualTo(2);

    documents.releaseCleanupAfterFailure(doc, stale.token(), true, "旧领取失败");
    cleanup.clean(stale);
    assertThat(cleanupStatus()).isEqualTo("running");
    assertThat(count("SELECT count(*) FROM chunk WHERE version_id = ?")).isGreaterThan(0);

    cleanup.clean(current);
    assertThat(cleanupStatus()).isEqualTo("done");
    assertThat(count("SELECT count(*) FROM chunk WHERE version_id = ?")).isZero();
  }

  @Test
  void activeCleanupLeaseIsNotStolen() throws Exception {
    deleteDocument().andExpect(status().isAccepted());
    jdbc.update("UPDATE document SET cleanup_status = 'running', cleanup_lease_until = ?, cleanup_attempts = 1 WHERE id = ?",
        Timestamp.from(Instant.now().plusSeconds(300)), doc);
    assertThat(cleanup.processNext()).isFalse();
  }

  @Test
  void exhaustedCleanupIsMarkedFailedAndCanBeRetried() throws Exception {
    deleteDocument().andExpect(status().isAccepted());
    jdbc.update("UPDATE document SET cleanup_status = 'running', cleanup_lease_until = ?, cleanup_attempts = 3 WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), doc);
    assertThat(cleanup.processNext()).isFalse();
    assertThat(cleanupStatus()).isEqualTo("failed");
    mvc.perform(get("/api/knowledge-bases/{kb}/documents", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$[0].deletion.status").value("failed"))
        .andExpect(jsonPath("$[0].deletion.error").isString());

    deleteDocument().andExpect(status().isAccepted());
    assertThat(cleanupStatus()).isEqualTo("pending");
    assertThat(cleanup.processNext()).isTrue();
    assertThat(cleanupStatus()).isEqualTo("done");
  }

  @Test
  void repeatedDeletionOfACleanedDocumentChangesNothing() throws Exception {
    deleteDocument().andExpect(status().isAccepted());
    cleanup.processNext();
    deleteDocument().andExpect(status().isAccepted());
    assertThat(cleanupStatus()).isEqualTo("done");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'DOCUMENT_DELETE' AND target = ?", Integer.class, "document:" + doc))
        .isEqualTo(1);
  }
}
