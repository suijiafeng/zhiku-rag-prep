package dev.zhiku.overview;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class OverviewTest extends IntegrationTest {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  final UUID enterprise = UUID.randomUUID();
  final UUID me = UUID.randomUUID();
  final UUID colleague = UUID.randomUUID();
  UUID kb;

  @BeforeEach
  void seed() {
    kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "总览-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, me, kb, colleague);
    var ready = version(document("员工手册"), "v3.2", "ready");
    version(document("差旅标准"), "v2", "failed");
    for (int i = 0; i < 3; i++) {
      var chunk = UUID.randomUUID();
      jdbc.update("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, text) VALUES (?, ?, ?, 0, 5, '年假')", chunk, ready, i);
      jdbc.update("INSERT INTO chunk_embedding (chunk_id, model, dimension, embedding) VALUES (?, 'ollama:bge-m3', 2, CAST('[1,0]' AS vector))", chunk);
    }
    jdbc.update("INSERT INTO ingestion_job (id, version_id, status, updated_at) VALUES (?, ?, 'succeeded', now())", UUID.randomUUID(), ready);
    var today = LocalDate.now(ZONE).atStartOfDay(ZONE).plusHours(9).toInstant();
    var yesterday = today.minusSeconds(86_400);
    message(me, "年假有几天?", today, "up");
    message(colleague, "报销多久到账?", today, "down");
    message(colleague, "VPN 怎么申请?", yesterday, null);
  }

  UUID document(String title) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", id, kb, title, me);
    return id;
  }

  UUID version(UUID doc, String label, String indexStatus) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, index_status) VALUES (?, ?, ?, now(), ?, ?)",
        id, doc, label, me, indexStatus);
    return id;
  }

  void message(UUID user, String question, Instant at, String rating) {
    var conversation = UUID.randomUUID();
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, ?)", conversation, enterprise, user, kb, question);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, created_at) VALUES (?, ?, ?, '答[1]', 'test', ?)",
        id, conversation, question, Timestamp.from(at));
    jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, 1, ?, ?)", id, UUID.randomUUID(), UUID.randomUUID());
    if (rating != null) {
      jdbc.update("INSERT INTO feedback (message_id, user_id, rating, reason) VALUES (?, ?, ?, ?)", id, user, rating, "down".equals(rating) ? "WRONG" : null);
    }
  }

  ResultActions overview(UUID user, Role... roles) throws Exception {
    return mvc.perform(get("/api/knowledge-bases/{kb}/overview", kb).header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles))));
  }

  @Test
  void summarisesDocumentsIndexAndQuestionsFromRealData() throws Exception {
    overview(me, Role.EMPLOYEE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documents").value(2))
        .andExpect(jsonPath("$.versions").value(2))
        .andExpect(jsonPath("$.readyVersions").value(1))
        .andExpect(jsonPath("$.chunks").value(3))
        .andExpect(jsonPath("$.vectors").value(3))
        .andExpect(jsonPath("$.questionsToday").value(2))
        .andExpect(jsonPath("$.questionsYesterday").value(1))
        .andExpect(jsonPath("$.feedback.up").value(1))
        .andExpect(jsonPath("$.feedback.down").value(1))
        .andExpect(jsonPath("$.embeddingSpaces[0].model").value("ollama:bge-m3"))
        .andExpect(jsonPath("$.embeddingSpaces[0].dimension").value(2))
        .andExpect(jsonPath("$.embeddingSpaces[0].vectors").value(3))
        .andExpect(jsonPath("$.lastIndexedAt").isString())
        .andExpect(jsonPath("$.recentDocuments.length()").value(2));
  }

  @Test
  void dailySeriesCoversSevenBusinessDaysEndingToday() throws Exception {
    var today = LocalDate.now(ZONE);
    overview(me, Role.EMPLOYEE)
        .andExpect(jsonPath("$.daily.length()").value(7))
        .andExpect(jsonPath("$.daily[6].day").value(today.toString()))
        .andExpect(jsonPath("$.daily[6].count").value(2))
        .andExpect(jsonPath("$.daily[5].day").value(today.minusDays(1).toString()))
        .andExpect(jsonPath("$.daily[5].count").value(1))
        .andExpect(jsonPath("$.daily[0].count").value(0));
  }

  @Test
  void recentQuestionsHideOtherPeoplesText() throws Exception {
    var body = overview(me, Role.EMPLOYEE).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("年假有几天?").doesNotContain("报销多久到账").doesNotContain("VPN");
    overview(me, Role.EMPLOYEE).andExpect(jsonPath("$.recentQuestions.length()").value(3));
  }

  @Test
  void requiresAccessToTheKnowledgeBase() throws Exception {
    overview(UUID.randomUUID(), Role.EMPLOYEE).andExpect(status().isForbidden());
    overview(me).andExpect(status().isForbidden());
  }
}
