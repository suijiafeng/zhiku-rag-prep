package dev.zhiku.chat;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class SourceTextVisibilityTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  final UUID enterprise = UUID.randomUUID();
  final UUID user = UUID.randomUUID();
  final UUID kb = UUID.randomUUID();
  final UUID conversation = UUID.randomUUID();
  final UUID independent = UUID.randomUUID();
  final UUID first = UUID.randomUUID();
  final UUID followUp = UUID.randomUUID();
  final UUID doc = UUID.randomUUID();
  final UUID version = UUID.randomUUID();
  final UUID chunk = UUID.randomUUID();
  static final String COPIED = "虚构来源甲：蓝色用品每周登记";
  static final String DERIVED = "沿用蓝色用品登记的条件";
  static final String INDEPENDENT = "独立问题：帮助入口在哪里";

  @BeforeEach
  void seed() {
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, '文本保护测试库')", kb, enterprise);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, user);
    source(doc, version, chunk);
    var nextVersion = UUID.randomUUID();
    var nextChunk = UUID.randomUUID();
    source(UUID.randomUUID(), nextVersion, nextChunk);
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)",
        conversation, enterprise, user, kb, COPIED, independent, enterprise, user, kb, INDEPENDENT);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, created_at) VALUES (?, ?, ?, '原答案[1]', 'test', now() - interval '1 minute'), "
        + "(?, ?, ?, '派生答案[1]', 'test', now())", first, conversation, COPIED, followUp, conversation, DERIVED);
    jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, 1, ?, ?), (?, 1, ?, ?)",
        first, version, chunk, followUp, nextVersion, nextChunk);
    jdbc.update("INSERT INTO message_context (message_id, depends_on) VALUES (?, ?)", followUp, first);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, status, error_code) VALUES (?, ?, ?, NULL, 'test', 'failed', 'NETWORK')",
        UUID.randomUUID(), independent, INDEPENDENT);
  }

  void source(UUID documentId, UUID versionId, UUID chunkId) {
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '虚构制度', ?)", documentId, kb, user);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, approved_at, published_at, index_status, created_by) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', now(), now(), 'ready', ?)", versionId, documentId, user);
    jdbc.update("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, text) VALUES (?, ?, 0, 0, 4, '虚构正文')", chunkId, versionId);
  }

  JsonNode read(String path) throws Exception {
    var text = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, Role.KB_OPERATOR))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"withdrawn", "deleted", "chunkMissing", "lineageMissing"})
  void unavailableSourcesHideQuestionsTitlesAndRecords(String change) throws Exception {
    switch (change) {
      case "withdrawn" -> jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
      case "deleted" -> jdbc.update("UPDATE document SET deleted_at = now(), cleanup_status = 'pending' WHERE id = ?", doc);
      case "chunkMissing" -> jdbc.update("DELETE FROM chunk WHERE id = ?", chunk);
      case "lineageMissing" -> jdbc.update("DELETE FROM message_source WHERE message_id = ?", first);
      default -> throw new AssertionError(change);
    }
    var messages = read("/api/conversations/" + conversation + "/messages");
    assertThat(messages.toString()).doesNotContain(COPIED, DERIVED, "原答案", "派生答案");
    assertThat(messages.get(0).path("hidden").asBoolean()).isTrue();
    assertThat(messages.get(1).path("hidden").asBoolean()).isTrue();
    assertThat(read("/api/conversations").toString()).doesNotContain(COPIED).contains(INDEPENDENT);
    var records = read("/api/knowledge-bases/" + kb + "/records");
    assertThat(records.toString()).doesNotContain(COPIED, DERIVED).contains(INDEPENDENT);
    assertThat(read("/api/conversations/" + independent + "/messages").get(0).path("question").asText()).isEqualTo(INDEPENDENT);
  }

  @Test
  void ordinaryExpiryKeepsHistoricalTextAndMarksAnswersStale() throws Exception {
    jdbc.update("UPDATE content_version SET effective_until = now() - interval '1 second' WHERE id = ?", version);
    var messages = read("/api/conversations/" + conversation + "/messages");
    assertThat(messages.get(0).path("question").asText()).isEqualTo(COPIED);
    assertThat(messages.get(0).path("hidden").asBoolean()).isFalse();
    assertThat(messages.get(0).path("stale").asBoolean()).isTrue();
    assertThat(messages.get(1).path("stale").asBoolean()).isTrue();
    assertThat(read("/api/conversations").toString()).contains(COPIED);
    assertThat(read("/api/knowledge-bases/" + kb + "/records").toString()).contains(COPIED, DERIVED);
  }

  @Test
  void restoringVerifiableSourcesRestoresAssociatedText() throws Exception {
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    assertThat(read("/api/conversations").toString()).doesNotContain(COPIED);
    jdbc.update("UPDATE content_version SET withdrawn_at = NULL WHERE id = ?", version);
    assertThat(read("/api/conversations/" + conversation + "/messages").toString()).contains(COPIED, DERIVED);
    assertThat(read("/api/conversations").toString()).contains(COPIED);
    assertThat(read("/api/knowledge-bases/" + kb + "/records").toString()).contains(COPIED, DERIVED);
  }
}
