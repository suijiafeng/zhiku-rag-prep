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
    embeddings.beforeEmbed = () -> {};
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

  /** 只保存已完成回答的正文；失败、拒答和取消的轮次只存为不含正文的状态记录。 */
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
    assertThat(events.subList(0, 2)).containsExactly("start", "draft");
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
  void anAnswerSharesItsRetrievalTraceAndAddsTheGenerationStage() throws Exception {
    var done = last(ask("年假有几天?")).data();
    var traceId = done.get("traceId").asText();
    var messageId = done.get("messageId").asText();
    mvc.perform(get("/api/knowledge-bases/{kb}/traces/{id}", kb, traceId).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.origin").value("chat"))
        .andExpect(jsonPath("$.messageId").value(messageId))
        .andExpect(jsonPath("$.stages.length()").value(6))
        .andExpect(jsonPath("$.stages[5].key").value("generation"))
        .andExpect(jsonPath("$.stages[5].status").value("success"))
        .andExpect(jsonPath("$.stages[5].summary").value(org.hamcrest.Matchers.containsString("test-scripted")));
  }

  @Test
  void recordsLinkEachAnswerToItsTrace() throws Exception {
    var traceId = last(ask("年假有几天?")).data().get("traceId").asText();
    mvc.perform(get("/api/knowledge-bases/{kb}/records", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$.items[0].traceId").value(traceId));
  }

  @Test
  void aFailedAnswerRecordsWhichStageFailedAndStaysLinkedToItsTurn() throws Exception {
    model.failMidway = true;
    var error = last(ask("年假有几天?")).data();
    var traceId = error.get("traceId").asText();
    var failedId = jdbc.queryForObject("SELECT m.id FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ? AND m.status = 'failed'",
        UUID.class, kb);
    mvc.perform(get("/api/knowledge-bases/{kb}/traces/{id}", kb, traceId).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$.stages[5].status").value("failed"))
        .andExpect(jsonPath("$.stages[5].summary").value(org.hamcrest.Matchers.containsString("MODEL_UNAVAILABLE")))
        .andExpect(jsonPath("$.messageId").value(failedId.toString()));
    mvc.perform(get("/api/knowledge-bases/{kb}/records", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$.items[0].traceId").value(traceId));
  }

  @Test
  void eventsCarryTheFieldsTheProtocolPromises() throws Exception {
    var frames = ask("年假有几天?");
    var start = frames.getFirst().data();
    assertThat(start.get("requestId").asText()).isNotBlank();
    var done = last(frames).data();
    assertThat(start.get("traceId").asText()).isEqualTo(done.get("traceId").asText());
    var seqs = frames.stream().filter(f -> f.event().equals("draft")).map(f -> f.data().get("seq").asInt()).toList();
    assertThat(seqs).isNotEmpty().isSorted().doesNotHaveDuplicates().startsWith(1);
    assertThat(done.get("finishReason").asText()).isEqualTo("stop");
    assertThat(done.get("latencyMs").isNumber()).isTrue();
  }

  @Test
  void errorsSayWhetherARetryCanHelpAndThatTheDraftIsDiscarded() throws Exception {
    model.failMidway = true;
    var failed = last(ask("年假有几天?")).data();
    assertThat(failed.get("retryable").asBoolean()).isTrue();
    assertThat(failed.get("discardDraft").asBoolean()).isTrue();
    model.reset();
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    var refused = last(ask("年假有几天?")).data();
    assertThat(refused.get("code").asText()).isEqualTo("NO_EVIDENCE");
    assertThat(refused.get("retryable").asBoolean()).isFalse();
  }

  @Test
  void questionsWithPersonalNumbersStopBeforeRetrievalAndAreStoredRedacted() throws Exception {
    var embedded = new java.util.concurrent.atomic.AtomicInteger();
    embeddings.beforeEmbed = embedded::incrementAndGet;
    model.script = m -> { throw new AssertionError("sensitive questions must not reach the model"); };
    var frames = ask("我的身份证号是110101199003071233，年假还剩几天");
    assertThat(names(frames)).containsExactly("start", "error");
    var error = last(frames).data();
    assertThat(error.get("code").asText()).isEqualTo("SENSITIVE_INPUT");
    assertThat(error.get("retryable").asBoolean()).isFalse();
    assertThat(error.get("message").asText()).contains("人工渠道");
    assertThat(embedded.get()).isZero();
    var stored = jdbc.queryForObject("SELECT m.question || '|' || c.title FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ?",
        String.class, kb);
    assertThat(stored).doesNotContain("110101199003071233").contains("[已隐藏]");
    assertThat(lastStatus()).isEqualTo("refused:SENSITIVE_INPUT:true");
  }

  @Test
  void theModelIsToldToAskForMissingConditionsAndNotToResolveConflicts() throws Exception {
    ask("年假有几天?");
    var system = model.lastPrompt.getFirst().content();
    assertThat(system).contains("需要确认的条件").contains("相互矛盾").contains("适用对象");
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
    assertThat(names(frames)).containsExactly("start", "error");
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
  void revocationAfterTheFirstDraftStopsFurtherDrafts() throws Exception {
    model.afterPiece = n -> {
      if (n == 0) jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, employee);
    };
    var frames = ask("年假有几天?");
    assertThat(frames.stream().filter(f -> f.event().equals("draft")).count()).isEqualTo(1);
    assertThat(last(frames).data().get("code").asText()).isEqualTo("ACCESS_REVOKED");
    assertThat(messages()).isZero();
  }

  @Test
  void withdrawalAfterTheFirstDraftStopsFurtherDrafts() throws Exception {
    model.afterPiece = n -> {
      if (n == 0) jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    };
    var frames = ask("年假有几天?");
    assertThat(frames.stream().filter(f -> f.event().equals("draft")).count()).isEqualTo(1);
    assertThat(last(frames).data().get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
  }

  @Test
  void withdrawalWhileTheQueryIsEmbeddedKeepsTheSourceFromTheModel() throws Exception {
    embeddings.beforeEmbed = () -> jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", version);
    model.script = m -> { throw new AssertionError("withdrawn sources must not reach the model"); };
    var frames = ask("年假有几天?");
    assertThat(names(frames)).containsExactly("start", "error");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("NO_EVIDENCE");
  }

  @Test
  void anAnswerLongerThanTheLimitIsStoppedAndNotStored() throws Exception {
    model.script = m -> "年假".repeat(150) + "[1]";
    var frames = ask("年假有几天?");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("ANSWER_TOO_LONG");
    var shown = frames.stream().filter(f -> f.event().equals("draft")).mapToInt(f -> f.data().get("text").asText().length()).sum();
    assertThat(shown).isLessThanOrEqualTo(200);
    assertThat(messages()).isZero();
  }

  @Test
  void aRebuildThatReplacesTheCitedChunksDuringGenerationStopsTheAnswer() throws Exception {
    model.beforeFinish = () -> {
      jdbc.update("DELETE FROM chunk WHERE version_id = ?", version);
      jdbc.update("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, text) VALUES (gen_random_uuid(), ?, 0, 0, 10, '年假按司龄计算')", version);
    };
    var frames = ask("年假有几天?");
    assertThat(names(frames)).doesNotContain("validated", "done");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
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
  void aRequestCancelledBeforeItStartsNeverEmbedsTheQuestion() {
    var scope = new AccessScope(enterprise, employee, Set.of(kb), Set.of(Role.EMPLOYEE));
    var embedded = new java.util.concurrent.atomic.AtomicInteger();
    embeddings.beforeEmbed = embedded::incrementAndGet;
    model.script = m -> { throw new AssertionError("cancelled requests must not reach the model"); };
    var sent = new ArrayList<String>();
    chat.stream(scope, kb, "年假有几天?", null, null, new ChatSink() {
      @Override public void send(String event, Object data) { sent.add(event); }
      @Override public boolean cancelled() { return true; }
    }, "test-request");
    assertThat(embedded.get()).isZero();
    assertThat(sent).isEmpty();
    assertThat(lastStatus()).isEqualTo("cancelled::true");
  }

  @Test
  void aCancelledSensitiveQuestionIsStoredRedactedToo() {
    var scope = new AccessScope(enterprise, employee, Set.of(kb), Set.of(Role.EMPLOYEE));
    chat.stream(scope, kb, "我的身份证号是110101199003071233，年假还剩几天", null, null, new ChatSink() {
      @Override public void send(String event, Object data) { }
      @Override public boolean cancelled() { return true; }
    }, "test-request");
    var stored = jdbc.queryForObject("SELECT m.question || '|' || c.title FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ?",
        String.class, kb);
    assertThat(stored).doesNotContain("110101199003071233").contains("[已隐藏]");
    assertThat(lastStatus()).isEqualTo("cancelled::true");
  }

  @Test
  void aRequestCancelledDuringRetrievalNeverReachesTheModel() {
    var scope = new AccessScope(enterprise, employee, Set.of(kb), Set.of(Role.EMPLOYEE));
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    embeddings.beforeEmbed = () -> cancelled.set(true);
    model.script = m -> { throw new AssertionError("cancelled requests must not reach the model"); };
    chat.stream(scope, kb, "年假有几天?", null, null, new ChatSink() {
      @Override public void send(String event, Object data) { }
      @Override public boolean cancelled() { return cancelled.get(); }
    }, "test-request");
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

  /** C64 评审复现：三个很长的前序回答后续问，实际送出的 prompt 总长不超过预算，省略的轮次记入轨迹。 */
  @Test
  void aFollowUpAfterLongAnswersStaysWithinTheContextBudget() throws Exception {
    var conversation = last(ask("年假有几天?")).data().get("conversationId").asText();
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "试用期呢?"));
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "实习生呢?"));
    jdbc.update("UPDATE message SET answer = repeat('长', 8000) WHERE conversation_id = ?::uuid", conversation);
    var frames = ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "那病假呢?"));
    assertThat(names(frames)).contains("done");
    assertThat(model.lastPrompt.stream().mapToInt(m -> m.content().length()).sum()).isLessThanOrEqualTo(16000);
    assertThat(model.lastPrompt.getLast().content()).contains("实习生呢?").doesNotContain("问：年假有几天?").contains("年假按司龄计算");
    var traceId = last(frames).data().get("traceId").asText();
    assertThat(jdbc.queryForObject("SELECT summary FROM retrieval_trace_stage WHERE trace_id = ?::uuid AND key = 'generation'", String.class, traceId))
        .contains("前序对话省略 2 轮");
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

  /** 新会话中一个较早的已完成轮次，基于来源 A 回答，而当前检索不再返回 A。 */
  UUID[] earlierTurnFromOtherSource() {
    var doc = UUID.randomUUID();
    var a = UUID.randomUUID();
    var chunk = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '旧制度', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v1', now(), ?)", a, doc, operator);
    jdbc.update("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, text) VALUES (?, ?, 0, 0, 8, '调休按小时折算')", chunk, a);
    var conversation = UUID.randomUUID();
    var turn = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, '调休')", conversation, enterprise, employee, kb);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, created_at) VALUES (?, ?, '调休怎么算?', '调休按小时折算[1]', 'test', now() - interval '1 minute')",
        turn, conversation);
    jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, 1, ?, ?)", turn, a, chunk);
    return new UUID[] {conversation, a};
  }

  /** 一个仍然合格的前序轮次：来源 A 已批准、已发布、索引就绪、当前有效。 */
  UUID[] earlierEligibleTurn() {
    var doc = UUID.randomUUID();
    var a = UUID.randomUUID();
    var chunk = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '调休制度', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, effective_until, created_by, approved_at, published_at, index_status) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', now() + interval '30 day', ?, now(), now(), 'ready')", a, doc, operator);
    jdbc.update("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, text) VALUES (?, ?, 0, 0, 8, '调休按小时折算')", chunk, a);
    var conversation = UUID.randomUUID();
    var turn = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, '调休')", conversation, enterprise, employee, kb);
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, created_at) VALUES (?, ?, '调休怎么算?', '调休按小时折算[1]', 'test', now() - interval '1 minute')",
        turn, conversation);
    jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, 1, ?, ?)", turn, a, chunk);
    return new UUID[] {conversation, a};
  }

  @Test
  void aFollowUpBecomesStaleWhenASourceOfItsContextExpires() throws Exception {
    var earlier = earlierEligibleTurn();
    var conversation = earlier[0].toString();
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "那年假呢?"));
    assertThat(model.lastPrompt.getLast().content()).contains("答：调休按小时折算");
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[1].stale").value(false));

    jdbc.update("UPDATE content_version SET effective_until = now() - interval '1 second' WHERE id = ?", earlier[1]);
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[0].stale").value(true))
        .andExpect(jsonPath("$[0].hidden").value(false))
        .andExpect(jsonPath("$[1].stale").value(true))
        .andExpect(jsonPath("$[1].hidden").value(false));
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "再问一次"));
    assertThat(model.lastPrompt.getLast().content()).doesNotContain("答：");
  }

  @Test
  void aContextAnswerExpiringDuringGenerationStopsTheFollowUp() throws Exception {
    var earlier = earlierEligibleTurn();
    model.afterPiece = n -> {
      if (n == 0) jdbc.update("UPDATE content_version SET effective_until = now() - interval '1 second' WHERE id = ?", earlier[1]);
    };
    var frames = ask(token(employee, Role.EMPLOYEE), continueBody(earlier[0].toString(), "那年假呢?"));
    assertThat(frames.stream().filter(f -> f.event().equals("draft")).count()).isEqualTo(1);
    assertThat(last(frames).data().get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
  }

  @Test
  void aConversationAtTheTurnLimitRefusesNewQuestions() throws Exception {
    var conversation = last(ask("年假有几天?")).data().get("conversationId").asText();
    for (int i = 0; i < 99; i++) {
      jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, status) VALUES (gen_random_uuid(), CAST(? AS uuid), '填充', NULL, 'test', 'refused')", conversation);
    }
    var frames = ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "还能问吗?"));
    assertThat(names(frames)).containsExactly("start", "error");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("CONVERSATION_TOO_LONG");
    assertThat(last(frames).data().get("retryable").asBoolean()).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE conversation_id = CAST(? AS uuid)", Integer.class, conversation)).isEqualTo(101);
  }

  @Test
  void followUpsAreHiddenWhenASourceOfTheirContextIsWithdrawn() throws Exception {
    var earlier = earlierTurnFromOtherSource();
    var conversation = earlier[0].toString();
    var done = ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "那年假呢?"));
    assertThat(names(done)).contains("done");
    assertThat(model.lastPrompt.getLast().content()).contains("调休怎么算?");
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[1].hidden").value(false))
        .andExpect(jsonPath("$[1].citations[0].versionId").value(version.toString()));

    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", earlier[1]);
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE)))
        .andExpect(jsonPath("$[0].hidden").value(true))
        .andExpect(jsonPath("$[1].hidden").value(true))
        .andExpect(jsonPath("$[1].answer").doesNotExist());
    ask(token(employee, Role.EMPLOYEE), continueBody(conversation, "再问一次"));
    assertThat(model.lastPrompt.getLast().content()).doesNotContain("此前对话").doesNotContain("那年假呢?");
  }

  @Test
  void withdrawingAContextSourceDuringGenerationStopsTheFollowUp() throws Exception {
    var earlier = earlierTurnFromOtherSource();
    model.beforeFinish = () -> jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", earlier[1]);
    var frames = ask(token(employee, Role.EMPLOYEE), continueBody(earlier[0].toString(), "那年假呢?"));
    assertThat(names(frames)).doesNotContain("validated", "done");
    assertThat(last(frames).data().get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
  }

  @Test
  void withdrawingAContextSourceAfterTheFirstDraftStopsFurtherDrafts() throws Exception {
    var earlier = earlierTurnFromOtherSource();
    model.afterPiece = n -> {
      if (n == 0) jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ?", earlier[1]);
    };
    var frames = ask(token(employee, Role.EMPLOYEE), continueBody(earlier[0].toString(), "那年假呢?"));
    assertThat(frames.stream().filter(f -> f.event().equals("draft")).count()).isEqualTo(1);
    assertThat(last(frames).data().get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
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
