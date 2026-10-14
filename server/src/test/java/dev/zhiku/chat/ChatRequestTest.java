package dev.zhiku.chat;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** PRD Q04: a client request id is accepted once; repeats never start another generation, and the outcome can be looked up. */
class ChatRequestTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired IngestionWorker worker;
  @Autowired TestChatModel model;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  final UUID employee = UUID.randomUUID();
  final AtomicInteger calls = new AtomicInteger();
  UUID kb;

  @BeforeEach
  void seed() throws Exception {
    model.reset();
    model.script = m -> {
      calls.incrementAndGet();
      return "年假按司龄计算[1]。";
    };
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "幂等-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, operator, kb, employee);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '员工手册', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())", version, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", "handbook.md", "text/markdown", "# 年假\n\n年假按司龄计算。".getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)))).andExpect(status().isAccepted());
    worker.processNext();
  }

  @AfterEach
  void reset() {
    model.reset();
  }

  String token(UUID user) {
    return bearer(TestJwt.token(enterprise, user, Role.EMPLOYEE));
  }

  String body(UUID clientRequestId, String question) {
    return "{\"kbId\":\"" + kb + "\",\"question\":\"" + question + "\",\"clientRequestId\":\"" + clientRequestId + "\"}";
  }

  ResultActions post(UUID user, String body) throws Exception {
    return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/chat")
        .header(HttpHeaders.AUTHORIZATION, token(user)).contentType("application/json").content(body));
  }

  String stream(UUID user, String body) throws Exception {
    var started = post(user, body).andExpect(request().asyncStarted()).andReturn();
    started.getAsyncResult(10_000);
    return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  ResultActions lookup(UUID user, UUID clientRequestId) throws Exception {
    return mvc.perform(get("/api/chat/requests/{id}", clientRequestId).header(HttpHeaders.AUTHORIZATION, token(user)));
  }

  @Test
  void repeatingARequestIdNeverStartsAnotherGeneration() throws Exception {
    var id = UUID.randomUUID();
    assertThat(stream(employee, body(id, "年假有几天?"))).contains("event:done");
    post(employee, body(id, "年假有几天?")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_DUPLICATE"));
    post(employee, body(id, "病假怎么请?")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));
    assertThat(calls.get()).isEqualTo(1);

    var messageId = jdbc.queryForObject("SELECT m.id FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ?", UUID.class, kb);
    lookup(employee, id).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("complete"))
        .andExpect(jsonPath("$.messageId").value(messageId.toString()))
        .andExpect(jsonPath("$.conversationId").isString())
        .andExpect(jsonPath("$.answer").doesNotExist());
  }

  @Test
  void theOutcomeOfAFailedOrRefusedTurnCanBeLookedUp() throws Exception {
    var failed = UUID.randomUUID();
    model.failMidway = true;
    stream(employee, body(failed, "年假有几天?"));
    lookup(employee, failed).andExpect(jsonPath("$.status").value("failed")).andExpect(jsonPath("$.messageId").isString());

    model.reset();
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE document_id IN (SELECT id FROM document WHERE kb_id = ?)", kb);
    var refused = UUID.randomUUID();
    stream(employee, body(refused, "年假有几天?"));
    lookup(employee, refused).andExpect(jsonPath("$.status").value("refused"));
  }

  @Test
  void acceptedRequestsWithoutAnOutcomeAreRunningUntilTheStreamWouldHaveTimedOut() throws Exception {
    var running = UUID.randomUUID();
    var lost = UUID.randomUUID();
    jdbc.update("INSERT INTO chat_request (enterprise_id, user_id, client_request_id, kb_id, digest) VALUES (?, ?, ?, ?, repeat('a', 64))",
        enterprise, employee, running, kb);
    jdbc.update("INSERT INTO chat_request (enterprise_id, user_id, client_request_id, kb_id, digest, accepted_at) "
        + "VALUES (?, ?, ?, ?, repeat('a', 64), now() - interval '1 hour')", enterprise, employee, lost, kb);
    lookup(employee, running).andExpect(jsonPath("$.status").value("running")).andExpect(jsonPath("$.messageId").doesNotExist());
    lookup(employee, lost).andExpect(jsonPath("$.status").value("interrupted"));
  }

  @Test
  void requestIdsBelongToOneUserAndStillRequireAccess() throws Exception {
    var id = UUID.randomUUID();
    stream(employee, body(id, "年假有几天?"));
    lookup(operator, id).andExpect(status().isNotFound());
    lookup(UUID.randomUUID(), id).andExpect(status().isNotFound());
    assertThat(stream(operator, body(id, "年假有几天?"))).contains("event:done");

    jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, employee);
    lookup(employee, id).andExpect(status().isForbidden());
  }

  @Test
  void requestsRejectedBeforeStreamingDoNotConsumeTheId() throws Exception {
    var id = UUID.randomUUID();
    post(employee, "{\"kbId\":\"" + UUID.randomUUID() + "\",\"question\":\"年假?\",\"clientRequestId\":\"" + id + "\"}")
        .andExpect(status().isForbidden());
    assertThat(stream(employee, body(id, "年假有几天?"))).contains("event:done");
  }
}
