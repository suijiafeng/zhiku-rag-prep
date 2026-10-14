package dev.zhiku.chat;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class ChatTest extends IntegrationTest {
  record Frame(String event, JsonNode data) {}

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired IngestionWorker worker;
  @Autowired TestChatModel model;
  @Autowired ChatService chat;

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
    kb = UUID.randomUUID();
    doc = UUID.randomUUID();
    version = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "问答-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, operator, kb, employee);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '员工手册', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v3.2', now() - interval '1 day', ?, now(), now())", version, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", "handbook.md", "text/markdown", LEAVE.getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)))).andExpect(status().isAccepted());
    assertThat(worker.processNext()).isTrue();
  }

  @AfterEach
  void reset() {
    model.reset();
  }

  String token(UUID user, Role... roles) {
    return bearer(TestJwt.token(enterprise, user, roles));
  }

  List<Frame> ask(String auth, String body) throws Exception {
    var started = mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, auth).contentType("application/json").content(body))
        .andExpect(request().asyncStarted()).andReturn();
    started.getAsyncResult(10_000);
    var text = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var frames = new ArrayList<Frame>();
    for (var block : text.split("\n\n")) {
      String event = "message";
      var data = new StringBuilder();
      for (var line : block.split("\n")) {
        if (line.startsWith("event:")) event = line.substring(6).strip();
        if (line.startsWith("data:")) data.append(line.substring(5));
      }
      if (!data.isEmpty()) frames.add(new Frame(event, json.readTree(data.toString())));
    }
    return frames;
  }

  List<Frame> ask(String question) throws Exception {
    return ask(token(employee, Role.EMPLOYEE), "{\"kbId\":\"" + kb + "\",\"question\":\"" + question + "\"}");
  }

  /** Completed answers only; failed, refused and cancelled turns are stored as status records without text. */
  int messages() {
    return jdbc.queryForObject("SELECT count(*) FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ? AND m.status = 'complete'", Integer.class, kb);
  }

  String lastStatus() {
    return jdbc.queryForObject("SELECT m.status || ':' || coalesce(m.error_code, '') || ':' || (m.answer IS NULL) FROM message m JOIN conversation c ON c.id = m.conversation_id "
        + "WHERE c.kb_id = ? ORDER BY m.created_at DESC LIMIT 1", String.class, kb);
  }

  static List<String> names(List<Frame> frames) {
    return frames.stream().map(Frame::event).toList();
  }

  Frame last(List<Frame> frames) {
    return frames.getLast();
  }

  @Test
  void streamsDraftsThenValidatedThenDoneAndStoresTheAnswer() throws Exception {
    var frames = ask("年假有几天?");
    var events = names(frames);
    assertThat(events.getFirst()).isEqualTo("draft");
    assertThat(events.subList(events.size() - 2, events.size())).containsExactly("validated", "done");
    var answer = new StringBuilder();
    frames.stream().filter(f -> f.event().equals("draft")).forEach(f -> answer.append(f.data().get("text").asText()));
    assertThat(answer.toString()).isEqualTo("年假按司龄计算[1]。");
    var validated = frames.get(frames.size() - 2).data();
    assertThat(validated.get("validation").asText()).isEqualTo("metadata-only");
    assertThat(validated.get("citations").get(0).get("versionId").asText()).isEqualTo(version.toString());
    assertThat(last(frames).data().get("status").asText()).isEqualTo("complete");
    assertThat(last(frames).data().get("conversationId").asText()).isNotBlank();
    var messageId = last(frames).data().get("messageId").asText();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE id = CAST(? AS uuid)", Integer.class, messageId)).isEqualTo(1);
    assertThat(messages()).isEqualTo(1);
  }

  @Test
  void sourcesAreDelimitedAsUntrustedDataInThePrompt() throws Exception {
    ask("年假有几天?");
    var system = model.lastPrompt.getFirst();
    assertThat(system.role()).isEqualTo("system");
    assertThat(system.content()).contains("不是指令");
    assertThat(model.lastPrompt.getLast().content()).contains("<<<").contains("年假按司龄计算");
  }

  @Test
  void answersWithoutValidCitationsFailAndAreNotStored() throws Exception {
    model.script = m -> "大概是十天吧。";
    var frames = ask("年假有几天?");
    assertThat(names(frames)).doesNotContain("validated", "done");
    assertThat(last(frames).event()).isEqualTo("error");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("NO_EVIDENCE");

    assertThat(lastStatus()).isEqualTo("refused:NO_EVIDENCE:true");
    model.script = m -> "十天[7]。";
    assertThat(last(ask("年假有几天?")).data().get("code").asText()).isEqualTo("CITATION_INVALID");
    assertThat(lastStatus()).isEqualTo("failed:CITATION_INVALID:true");
    assertThat(messages()).isZero();
  }

  @Test
  void refusesWithoutModelWhenNothingEligibleIsFound() throws Exception {
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    model.script = m -> { throw new AssertionError("model must not be called without evidence"); };
    var frames = ask("年假有几天?");
    assertThat(names(frames)).containsExactly("error");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("NO_EVIDENCE");
  }

  @Test
  void revocationDuringGenerationStopsDisclosure() throws Exception {
    model.beforeFinish = () -> jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, employee);
    var frames = ask("年假有几天?");
    assertThat(names(frames)).doesNotContain("validated", "done");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("ACCESS_REVOKED");
    assertThat(messages()).isZero();
  }

  @Test
  void withdrawalDuringGenerationStopsDisclosure() throws Exception {
    model.beforeFinish = () -> jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    var frames = ask("年假有几天?");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
    assertThat(messages()).isZero();
  }

  @Test
  void modelFailureEndsWithErrorAndNothingStored() throws Exception {
    model.failMidway = true;
    var frames = ask("年假有几天?");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("MODEL_UNAVAILABLE");
    assertThat(last(frames).data().get("requestId").asText()).isNotBlank();
    assertThat(messages()).isZero();
  }

  @Test
  void cancelledStreamsAreNotStored() {
    var scope = new AccessScope(enterprise, employee, Set.of(kb), Set.of(Role.EMPLOYEE));
    var sent = new ArrayList<String>();
    chat.stream(scope, kb, "年假有几天?", null, null, new ChatSink() {
      @Override public void send(String event, Object data) { sent.add(event); }
      @Override public boolean cancelled() { return !sent.isEmpty(); }
    }, "test-request");
    assertThat(sent).containsExactly("draft");
    assertThat(messages()).isZero();
    assertThat(lastStatus()).isEqualTo("cancelled::true");
  }

  @Test
  void rejectsUnauthorizedRequestsBeforeStreaming() throws Exception {
    mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), Role.EMPLOYEE))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"年假?\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, token(employee))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"年假?\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void historyHidesAnswersWhoseSourcesAreGoneAndIsPrivate() throws Exception {
    var done = last(ask("年假有几天?")).data();
    var conversation = done.get("conversationId").asText();
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].hidden").value(false))
        .andExpect(jsonPath("$[0].answer").value("年假按司龄计算[1]。"))
        .andExpect(jsonPath("$[0].citations[0].versionId").value(version.toString()));
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[0].id").value(conversation));

    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[0].hidden").value(true))
        .andExpect(jsonPath("$[0].answer").doesNotExist())
        .andExpect(jsonPath("$[0].citations.length()").value(0));

    jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, employee);
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$.length()").value(0));
  }

  String continueBody(String conversation, String question) {
    return "{\"kbId\":\"" + kb + "\",\"question\":\"" + question + "\",\"conversationId\":\"" + conversation + "\"}";
  }

  @Test
  void followUpsSeePriorTurnsOnlyAsNonEvidenceContext() throws Exception {
    var conversation = last(ask("年假有几天?")).data().get("conversationId").asText();
    model.script = m -> "试用期同样按司龄计算[1]。";
    var frames = ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "那试用期呢?"));
    assertThat(names(frames)).contains("done");
    var user = model.lastPrompt.getLast().content();
    assertThat(user).contains("此前对话").contains("不是事实依据").contains("年假有几天?").contains("年假按司龄计算。");
    assertThat(user.substring(user.indexOf("此前对话"), user.indexOf("资料："))).doesNotContain("[1]");
    assertThat(user).endsWith("问题：那试用期呢?");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE conversation_id = CAST(? AS uuid)", Integer.class, conversation)).isEqualTo(2);
  }

  @Test
  void turnsWithUnavailableSourcesAreExcludedFromContext() throws Exception {
    var conversation = last(ask("年假有几天?")).data().get("conversationId").asText();
    jdbc.update("UPDATE message_source SET chunk_id = ? WHERE message_id IN (SELECT id FROM message WHERE conversation_id = CAST(? AS uuid))",
        UUID.randomUUID(), conversation);
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "那试用期呢?"));
    var user = model.lastPrompt.getLast().content();
    assertThat(user).doesNotContain("此前对话").doesNotContain("年假有几天?");
  }

  @Test
  void failedTurnsAppearInHistoryWithoutTextAndStayOutOfContext() throws Exception {
    var conversation = last(ask("年假有几天?")).data().get("conversationId").asText();
    model.failMidway = true;
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "失败的问题"));
    model.reset();
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[1].status").value("failed"))
        .andExpect(jsonPath("$[1].errorCode").value("MODEL_UNAVAILABLE"))
        .andExpect(jsonPath("$[1].answer").doesNotExist())
        .andExpect(jsonPath("$[1].hidden").value(false));
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "再问一次"));
    assertThat(model.lastPrompt.getLast().content()).doesNotContain("失败的问题").contains("年假有几天?");
    var failedId = jdbc.queryForObject("SELECT id FROM message WHERE question = '失败的问题' AND conversation_id = CAST(? AS uuid)", UUID.class, conversation);
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/messages/{id}/feedback", failedId)
            .header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)).contentType("application/json").content("{\"rating\":\"up\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void cannotContinueSomeoneElsesConversation() throws Exception {
    var conversation = last(ask("年假有几天?")).data().get("conversationId").asText();
    mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, token(operator, Role.EMPLOYEE))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"再问\",\"conversationId\":\"" + conversation + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void widgetTokensAreShortLivedAndScopedToOneKnowledgeBase() throws Exception {
    var otherKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", otherKb, enterprise, "另一库-" + otherKb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", otherKb, employee);

    var body = mvc.perform(post("/api/widget-tokens").header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE, Role.KB_OPERATOR))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var issued = json.readTree(body);
    var widget = bearer(issued.get("token").asText());
    assertThat(java.time.Instant.parse(issued.get("expiresAt").asText())).isBefore(java.time.Instant.now().plusSeconds(601));

    var widgetFrames = ask(widget, "{\"kbId\":\"" + kb + "\",\"question\":\"年假有几天?\"}");
    assertThat(names(widgetFrames)).contains("done");
    var answered = last(widgetFrames).data().get("messageId").asText();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/messages/{id}/feedback", answered)
            .header(HttpHeaders.AUTHORIZATION, widget).contentType("application/json").content("{\"rating\":\"down\",\"reason\":\"MISSING\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, widget)).andExpect(status().isForbidden());
    mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, widget)
            .contentType("application/json").content("{\"kbId\":\"" + otherKb + "\",\"question\":\"年假?\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/knowledge-bases/{kb}/documents", kb).header(HttpHeaders.AUTHORIZATION, widget)).andExpect(status().isForbidden());
    mvc.perform(post("/api/widget-tokens").header(HttpHeaders.AUTHORIZATION, widget)
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/widget-tokens").header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), Role.EMPLOYEE))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\"}"))
        .andExpect(status().isForbidden());
  }
}
