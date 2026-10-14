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

  ResultActions handle(UUID messageId, String body) throws Exception {
    return mvc.perform(as(patch("/api/knowledge-bases/{kb}/feedback/{id}", kb, messageId), operator, Role.KB_OPERATOR)
        .contentType("application/json").content(body));
  }

  UUID evalCase(String status) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO eval_case (id, kb_id, question, label, status, created_by) VALUES (?, ?, '年假有几天?', 'answerable', ?, ?)", id, kb, status, operator);
    return id;
  }

  void run(UUID caseId, boolean hit, int minutesAgo) {
    var runId = UUID.randomUUID();
    jdbc.update("INSERT INTO eval_run (id, kb_id, k, created_by, created_at, summary) VALUES (?, ?, 5, ?, now() - make_interval(mins => ?), '{}')",
        runId, kb, operator, minutesAgo);
    jdbc.update("INSERT INTO eval_result (run_id, case_id, mapping_failed, hit, evidence_hit, evidence_total, latency_ms) VALUES (?, ?, false, ?, ?, 1, 10)",
        runId, caseId, hit, hit ? 1 : 0);
  }

  static String resolve(UUID caseId) {
    return "{\"status\":\"resolved\",\"rootCause\":\"文档未就绪或版本错误\",\"resolution\":\"发布新版本并重建索引\",\"verificationCaseId\":\"" + caseId + "\"}";
  }

  @Test
  void resolvingRequiresAFixAndAnApprovedCaseThatPassedTheLatestRun() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"OUTDATED\"}").andExpect(status().isOk());
    var passing = evalCase("approved");
    handle(askerMessage, "{\"status\":\"resolved\",\"rootCause\":\"文档未就绪或版本错误\",\"verificationCaseId\":\"" + passing + "\"}")
        .andExpect(status().isUnprocessableEntity());
    handle(askerMessage, "{\"status\":\"resolved\",\"resolution\":\"已修复\",\"verificationCaseId\":\"" + passing + "\"}")
        .andExpect(status().isUnprocessableEntity());
    handle(askerMessage, "{\"status\":\"resolved\",\"rootCause\":\"文档未就绪或版本错误\",\"resolution\":\"已修复\"}")
        .andExpect(status().isUnprocessableEntity());

    handle(askerMessage, resolve(evalCase("candidate"))).andExpect(status().isUnprocessableEntity());
    handle(askerMessage, resolve(passing)).andExpect(status().isUnprocessableEntity());
    run(passing, true, 10);
    run(passing, false, 1);
    handle(askerMessage, resolve(passing)).andExpect(status().isUnprocessableEntity());
    run(passing, true, 0);

    var otherKb = UUID.randomUUID();
    var foreign = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", otherKb, enterprise, "其他-" + otherKb);
    jdbc.update("INSERT INTO eval_case (id, kb_id, question, label, status, created_by) VALUES (?, ?, 'x', 'answerable', 'approved', ?)", foreign, otherKb, operator);
    handle(askerMessage, resolve(foreign)).andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("SELECT status FROM feedback WHERE message_id = ?", String.class, askerMessage)).isEqualTo("open");

    handle(askerMessage, resolve(passing)).andExpect(status().isNoContent());
    records("down")
        .andExpect(jsonPath("$.items[0].status").value("resolved"))
        .andExpect(jsonPath("$.items[0].resolution").value("发布新版本并重建索引"))
        .andExpect(jsonPath("$.items[0].verificationCaseId").value(passing.toString()));
  }

  @Test
  void closingWithoutFixNeedsAReason() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"WRONG\"}").andExpect(status().isOk());
    handle(askerMessage, "{\"status\":\"wont_fix\"}").andExpect(status().isUnprocessableEntity());
    handle(askerMessage, "{\"status\":\"wont_fix\",\"resolution\":\"  \"}").andExpect(status().isUnprocessableEntity());
    handle(askerMessage, "{\"status\":\"wont_fix\",\"resolution\":\"" + "长".repeat(1001) + "\"}").andExpect(status().isUnprocessableEntity());
    handle(askerMessage, "{\"status\":\"wont_fix\",\"resolution\":\"问题超出试点范围，已转人工渠道\"}").andExpect(status().isNoContent());
  }

  @Test
  void everyHandlingChangeIsKeptInHistory() throws Exception {
    rate(askerMessage, asker, "{\"rating\":\"down\",\"reason\":\"MISSING\"}").andExpect(status().isOk());
    handle(askerMessage, "{\"status\":\"in_progress\",\"rootCause\":\"解析/切块遗漏\"}").andExpect(status().isNoContent());
    handle(askerMessage, "{\"status\":\"wont_fix\",\"resolution\":\"资料本身未覆盖\"}").andExpect(status().isNoContent());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/feedback/{id}/history", kb, askerMessage), operator, Role.KB_OPERATOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].status").value("in_progress"))
        .andExpect(jsonPath("$[0].rootCause").value("解析/切块遗漏"))
        .andExpect(jsonPath("$[0].changedBy").value(operator.toString()))
        .andExpect(jsonPath("$[1].status").value("wont_fix"))
        .andExpect(jsonPath("$[1].resolution").value("资料本身未覆盖"));
    mvc.perform(as(get("/api/knowledge-bases/{kb}/feedback/{id}/history", kb, askerMessage), asker, Role.EMPLOYEE)).andExpect(status().isForbidden());
    var otherKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", otherKb, enterprise, "其他-" + otherKb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", otherKb, operator);
    mvc.perform(as(get("/api/knowledge-bases/{kb}/feedback/{id}/history", otherKb, askerMessage), operator, Role.KB_OPERATOR))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
  }

  /** A completed answer whose single source really exists, so withdrawing it makes the answer unavailable. */
  UUID[] answeredFromRealSource(UUID user) {
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    var chunk = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '员工手册', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at, index_status) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now(), 'ready')", version, doc, operator);
    jdbc.update("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, text) VALUES (?, ?, 0, 0, 10, '年假按司龄计算')", chunk, version);
    var conversation = UUID.randomUUID();
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, '年假')", conversation, enterprise, user, kb);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model) VALUES (?, ?, '年假有几天?', '年假按司龄计算[1]', 'test')", id, conversation);
    jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, 1, ?, ?)", id, version, chunk);
    return new UUID[] {id, conversation, version};
  }

  @Test
  void commentsAreHiddenWithTheAnswerWhenItsSourceIsUnavailable() throws Exception {
    var answered = answeredFromRealSource(operator);
    rate(answered[0], operator, "{\"rating\":\"down\",\"reason\":\"WRONG\",\"comment\":\"原文写的是年假按司龄计算\"}").andExpect(status().isOk());
    mvc.perform(as(get("/api/conversations/{id}/messages", answered[1]), operator, Role.EMPLOYEE))
        .andExpect(jsonPath("$[0].hidden").value(false))
        .andExpect(jsonPath("$[0].feedback.comment").value("原文写的是年假按司龄计算"));
    records("down").andExpect(jsonPath("$.items[0].comment").value("原文写的是年假按司龄计算"));

    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", answered[2]);
    var history = mvc.perform(as(get("/api/conversations/{id}/messages", answered[1]), operator, Role.EMPLOYEE))
        .andExpect(jsonPath("$[0].hidden").value(true))
        .andExpect(jsonPath("$[0].feedback.rating").value("down"))
        .andExpect(jsonPath("$[0].feedback.reason").value("WRONG"))
        .andReturn().getResponse().getContentAsString();
    assertThat(history).doesNotContain("原文写的是");
    var records = records("down").andExpect(jsonPath("$.items[0].rating").value("down"))
        .andExpect(jsonPath("$.items[0].sourceAvailable").value(false))
        .andReturn().getResponse().getContentAsString();
    assertThat(records).doesNotContain("原文写的是");
  }

  @Test
  void recordsTreatAnswersBuiltOnAnUnavailableEarlierTurnAsUnavailable() throws Exception {
    var earlier = answeredFromRealSource(operator);
    var later = answeredFromRealSource(operator);
    jdbc.update("INSERT INTO message_context (message_id, depends_on) VALUES (?, ?)", later[0], earlier[0]);
    rate(later[0], operator, "{\"rating\":\"down\",\"reason\":\"WRONG\",\"comment\":\"沿用了上一轮的说法\"}").andExpect(status().isOk());
    records("down").andExpect(jsonPath("$.items[0].sourceAvailable").value(true));
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", earlier[2]);
    var body = records("down").andExpect(jsonPath("$.items[0].sourceAvailable").value(false)).andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("沿用了上一轮的说法");
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
