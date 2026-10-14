package dev.zhiku.chat;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class FeedbackTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  final UUID enterprise = UUID.randomUUID();
  final UUID asker = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  UUID kb;
  UUID askerMessage;
  UUID operatorMessage;
  UUID unratedMessage;

  @BeforeEach
  void seed() {
    kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "反馈-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, asker, kb, operator);
    askerMessage = message(asker, "年假有几天?");
    operatorMessage = message(operator, "报销多久到账?");
    unratedMessage = message(asker, "VPN 怎么申请?");
  }

  UUID message(UUID user, String question) {
    var conversation = UUID.randomUUID();
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, ?)", conversation, enterprise, user, kb, question);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model) VALUES (?, ?, ?, '答案[1]', 'test')", id, conversation, question);
    jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, 1, ?, ?)", id, UUID.randomUUID(), UUID.randomUUID());
    return id;
  }

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, UUID user, Role... roles) {
    return req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)));
  }

  ResultActions rate(UUID messageId, UUID user, String body) throws Exception {
    return mvc.perform(as(put("/api/messages/{id}/feedback", messageId), user, Role.EMPLOYEE).contentType("application/json").content(body));
  }

  ResultActions records(String filter) throws Exception {
    return mvc.perform(as(get("/api/knowledge-bases/{kb}/records", kb).param("filter", filter), operator, Role.KB_OPERATOR));
  }

  @Test
  void ownerRatesAndChangesFeedbackWithHistory() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"BAD_CITATION\",\"comment\":\"引用指向了差旅标准\"}")
        .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.reason").value("BAD_CITATION"));
    rate(askerMessage, asker, "{\"rating\":\"up\",\"reason\":\"WRONG\"}")
        .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.rating").value("up"))
        .andExpect(jsonPath("$.reason").doesNotExist());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback_history WHERE message_id = ?", Integer.class, askerMessage)).isEqualTo(2);
  }

  @Test
  void validatesFeedback() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\"}").andExpect(status().isUnprocessableEntity());
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"NOPE\"}").andExpect(status().isUnprocessableEntity());
    rate(askerMessage, asker, "{\"rating\":\"meh\"}").andExpect(status().isUnprocessableEntity());
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"WRONG\",\"comment\":\"" + "长".repeat(501) + "\"}").andExpect(status().isUnprocessableEntity());
  }

  @Test
  void onlyTheAskerWithCurrentAccessCanRate() throws Exception {
    rate(askerMessage, operator, "{\"rating\":\"up\"}").andExpect(status().isForbidden());
    rate(UUID.randomUUID(), asker, "{\"rating\":\"up\"}").andExpect(status().isForbidden());
    jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, asker);
    rate(askerMessage, asker, "{\"rating\":\"up\"}").andExpect(status().isForbidden());
  }

  @Test
  void operatorsSeeRecordMetadataButNotOtherPeoplesText() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"WRONG\",\"comment\":\"不对\"}").andExpect(status().isOk());
    rate(operatorMessage, operator, "{\"rating\":\"up\"}").andExpect(status().isOk());
    var body = records("all").andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.stats.up").value(1))
        .andExpect(jsonPath("$.stats.down").value(1))
        .andExpect(jsonPath("$.stats.unrated").value(1))
        .andExpect(jsonPath("$.stats.reasons.WRONG").value(1))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("年假有几天").doesNotContain("VPN").doesNotContain("不对").contains("报销多久到账");
    records("down").andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].messageId").value(askerMessage.toString()))
        .andExpect(jsonPath("$.items[0].own").value(false))
        .andExpect(jsonPath("$.items[0].question").doesNotExist())
        .andExpect(jsonPath("$.items[0].reason").value("WRONG"));
    records("none").andExpect(jsonPath("$.items[0].messageId").value(unratedMessage.toString()));
  }

  @Test
  void employeesCannotListRecords() throws Exception {
    mvc.perform(as(get("/api/knowledge-bases/{kb}/records", kb), asker, Role.EMPLOYEE)).andExpect(status().isForbidden());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/records", kb), UUID.randomUUID(), Role.KB_OPERATOR)).andExpect(status().isForbidden());
  }

  @Test
  void operatorsTrackHandlingStatusWithAudit() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"OUTDATED\"}").andExpect(status().isOk());
    mvc.perform(as(patch("/api/knowledge-bases/{kb}/feedback/{id}", kb, askerMessage), operator, Role.KB_OPERATOR)
            .contentType("application/json").content("{\"status\":\"in_progress\",\"rootCause\":\"文档未就绪或版本错误\"}"))
        .andExpect(status().isNoContent());
    records("down").andExpect(jsonPath("$.items[0].status").value("in_progress")).andExpect(jsonPath("$.items[0].rootCause").value("文档未就绪或版本错误"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'FEEDBACK_STATUS' AND target = ?", Integer.class, "message:" + askerMessage)).isEqualTo(1);

    mvc.perform(as(patch("/api/knowledge-bases/{kb}/feedback/{id}", kb, askerMessage), operator, Role.KB_OPERATOR)
        .contentType("application/json").content("{\"status\":\"done\"}")).andExpect(status().isUnprocessableEntity());
    mvc.perform(as(patch("/api/knowledge-bases/{kb}/feedback/{id}", kb, askerMessage), operator, Role.KB_OPERATOR)
        .contentType("application/json").content("{\"status\":\"resolved\",\"rootCause\":\"模型问题\"}")).andExpect(status().isUnprocessableEntity());
    mvc.perform(as(patch("/api/knowledge-bases/{kb}/feedback/{id}", kb, askerMessage), asker, Role.EMPLOYEE)
        .contentType("application/json").content("{\"status\":\"resolved\"}")).andExpect(status().isForbidden());
    mvc.perform(as(patch("/api/knowledge-bases/{kb}/feedback/{id}", kb, unratedMessage), operator, Role.KB_OPERATOR)
        .contentType("application/json").content("{\"status\":\"resolved\"}")).andExpect(status().isNotFound());
  }

  @Test
  void historyShowsTheAskersOwnFeedback() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"MISSING\"}").andExpect(status().isOk());
    var conversation = jdbc.queryForObject("SELECT conversation_id FROM message WHERE id = ?", UUID.class, askerMessage);
    mvc.perform(as(get("/api/conversations/{id}/messages", conversation), asker, Role.EMPLOYEE))
        .andExpect(jsonPath("$[0].feedback.rating").value("down"))
        .andExpect(jsonPath("$[0].feedback.reason").value("MISSING"));
  }
}
