package dev.zhiku.config;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestEmbeddingProvider;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
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

/** PRD C01：候选 → 验证 → 发布，乐观锁，回滚；发布后的配置作用于新的问答请求。 */
class ConfigTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired IngestionWorker worker;
  @Autowired TestChatModel model;
  @Autowired TestEmbeddingProvider embeddings;
  @Autowired ConfigRepository repository;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  final UUID employee = UUID.randomUUID();
  UUID kb;

  @BeforeEach
  void seed() throws Exception {
    model.reset();
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "配置-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, operator, kb, employee);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '员工手册', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())", version, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", "handbook.md", "text/markdown", "# 年假\n\n年假按司龄计算。".getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))).andExpect(status().isAccepted());
    worker.processNext();
  }

  @AfterEach
  void reset() {
    model.reset();
    embeddings.failing.set(false);
  }

  String token(UUID user, Role... roles) {
    return bearer(TestJwt.token(enterprise, user, roles));
  }

  ResultActions getConfig() throws Exception {
    return mvc.perform(get("/api/knowledge-bases/{kb}/config", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)));
  }

  ResultActions save(String body) throws Exception {
    return mvc.perform(put("/api/knowledge-bases/{kb}/config/candidate", kb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))
        .contentType("application/json").content(body));
  }

  ResultActions action(String name, int expectedRevision) throws Exception {
    return mvc.perform(post("/api/knowledge-bases/{kb}/config/candidate/{action}", kb, name).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR))
        .contentType("application/json").content("{\"expectedRevision\":" + expectedRevision + "}"));
  }

  static String candidate(int k, int contextChunks, String channel, String addendum, String note, Integer expectedRevision) {
    return "{\"retrievalK\":" + k + ",\"contextChunks\":" + contextChunks + ",\"handoffChannel\":\"" + channel + "\",\"promptAddendum\":\"" + addendum
        + "\",\"note\":\"" + note + "\"" + (expectedRevision == null ? "" : ",\"expectedRevision\":" + expectedRevision) + "}";
  }

  int revision(ResultActions saved) throws Exception {
    return json.readTree(saved.andReturn().getResponse().getContentAsString()).get("revision").asInt();
  }

  /** 保存、验证并发布一个候选；返回发布后的版本号。 */
  int publish(int k, int contextChunks, String channel, String addendum) throws Exception {
    int rev = revision(save(candidate(k, contextChunks, channel, addendum, "联调", null)).andExpect(status().isOk()));
    action("validate", rev).andExpect(status().isOk());
    var body = json.readTree(action("publish", rev).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    return body.get("version").asInt();
  }

  JsonNode ask(String question) throws Exception {
    var started = mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"" + question + "\"}"))
        .andExpect(request().asyncStarted()).andReturn();
    started.getAsyncResult(10_000);
    var text = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    JsonNode start = null;
    JsonNode last = null;
    for (var block : text.split("\n\n")) {
      String event = "";
      var data = new StringBuilder();
      for (var line : block.split("\n")) {
        if (line.startsWith("event:")) event = line.substring(6).strip();
        if (line.startsWith("data:")) data.append(line.substring(5));
      }
      if (data.isEmpty()) continue;
      if (event.equals("start")) start = json.readTree(data.toString());
      last = json.readTree(data.toString());
    }
    var node = json.createObjectNode();
    node.set("start", start);
    node.set("last", last);
    return node;
  }

  @Test
  void withoutAPublishedVersionTheDeploymentDefaultsApply() throws Exception {
    getConfig().andExpect(status().isOk())
        .andExpect(jsonPath("$.published").doesNotExist())
        .andExpect(jsonPath("$.candidate").doesNotExist())
        .andExpect(jsonPath("$.defaults.retrievalK").value(8))
        .andExpect(jsonPath("$.defaults.contextChunks").value(5))
        .andExpect(jsonPath("$.history.length()").value(0));
    var frames = ask("年假有几天?");
    assertThat(frames.get("start").get("configVersion").asInt()).isZero();
  }

  @Test
  void aCandidateIsValidatedThenPublishedAndNewRequestsUseIt() throws Exception {
    int rev = revision(save(candidate(12, 3, "人事服务台 8001", "回答末尾提醒核对适用部门。", "调小入模证据", null)).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("candidate")).andExpect(jsonPath("$.validatedAt").doesNotExist()));
    action("publish", rev).andExpect(status().isConflict());

    action("validate", rev).andExpect(status().isOk())
        .andExpect(jsonPath("$.validation.evaluationRunId").isString())
        .andExpect(jsonPath("$.validation.cases").value(0))
        .andExpect(jsonPath("$.validation.humanQualityChecked").value(false));
    var published = json.readTree(action("publish", rev).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(published.get("version").asInt()).isEqualTo(1);
    assertThat(published.get("status").asText()).isEqualTo("published");
    getConfig().andExpect(jsonPath("$.published.version").value(1)).andExpect(jsonPath("$.candidate").doesNotExist())
        .andExpect(jsonPath("$.history[0].version").value(1));

    var frames = ask("年假有几天?");
    assertThat(frames.get("start").get("configVersion").asInt()).isEqualTo(1);
    assertThat(frames.get("last").get("status").asText()).isEqualTo("complete");
    assertThat(model.lastPrompt.getFirst().content()).contains("补充规则").contains("回答末尾提醒核对适用部门。");
    var traceId = frames.get("last").get("traceId").asText();
    mvc.perform(get("/api/knowledge-bases/{kb}/traces/{id}", kb, traceId).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$.k").value(12)).andExpect(jsonPath("$.configVersion").value(1));

    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE document_id IN (SELECT id FROM document WHERE kb_id = ?)", kb);
    assertThat(ask("年假有几天?").get("last").get("message").asText()).contains("人事服务台 8001");
  }

  @Test
  void concurrentEditsAreRejectedByTheRevisionCheck() throws Exception {
    int rev = revision(save(candidate(10, 4, "", "", "甲的修改", null)).andExpect(status().isOk()));
    save(candidate(10, 4, "", "", "乙的修改", rev)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(rev + 1));
    save(candidate(10, 4, "", "", "甲再改", rev)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_CONFLICT"));
    save(candidate(10, 4, "", "", "甲再改", null)).andExpect(status().isConflict());
    action("validate", rev).andExpect(status().isConflict());
    action("validate", rev + 1).andExpect(status().isOk());
    action("publish", rev).andExpect(status().isConflict());
    getConfig().andExpect(jsonPath("$.candidate.note").value("乙的修改"));
  }

  @Test
  void editingAfterValidationNeedsANewValidation() throws Exception {
    int rev = revision(save(candidate(10, 4, "", "", "", null)));
    action("validate", rev).andExpect(status().isOk());
    int next = revision(save(candidate(11, 4, "", "", "", rev)).andExpect(jsonPath("$.validatedAt").doesNotExist()));
    action("publish", next).andExpect(status().isConflict());
    action("validate", next).andExpect(status().isOk());
    action("publish", next).andExpect(status().isOk());
  }

  @Test
  void boundsAreCheckedOnSave() throws Exception {
    save(candidate(0, 1, "", "", "", null)).andExpect(status().isUnprocessableEntity());
    save(candidate(31, 1, "", "", "", null)).andExpect(status().isUnprocessableEntity());
    save(candidate(5, 6, "", "", "", null)).andExpect(status().isUnprocessableEntity());
    save(candidate(5, 0, "", "", "", null)).andExpect(status().isUnprocessableEntity());
    save(candidate(5, 5, "", "长".repeat(1001), "", null)).andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_config WHERE kb_id = ?", Integer.class, kb)).isZero();
  }

  @Test
  void validationFailsWhenRetrievalCannotBeEvaluated() throws Exception {
    int rev = revision(save(candidate(10, 4, "", "", "", null)));
    embeddings.failing.set(true);
    action("validate", rev).andExpect(status().isServiceUnavailable());
    action("publish", rev).andExpect(status().isConflict());
    getConfig().andExpect(jsonPath("$.candidate.validatedAt").doesNotExist());
  }

  @Test
  void rollbackRepublishesAnEarlierVersionAsANewOne() throws Exception {
    assertThat(publish(10, 4, "渠道一", "")).isEqualTo(1);
    assertThat(publish(20, 6, "渠道二", "")).isEqualTo(2);
    mvc.perform(post("/api/knowledge-bases/{kb}/config/versions/{v}/rollback", kb, 1).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.retrievalK").value(10))
        .andExpect(jsonPath("$.rolledBackFrom").value(1));
    getConfig().andExpect(jsonPath("$.published.version").value(3)).andExpect(jsonPath("$.history.length()").value(3))
        .andExpect(jsonPath("$.history[1].status").value("superseded"));
    mvc.perform(post("/api/knowledge-bases/{kb}/config/versions/{v}/rollback", kb, 9).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(status().isNotFound());
    assertThat(ask("年假有几天?").get("start").get("configVersion").asInt()).isEqualTo(3);
  }

  @Test
  void requiresTheEvaluateRoleAndAGrant() throws Exception {
    mvc.perform(get("/api/knowledge-bases/{kb}/config", kb).header(HttpHeaders.AUTHORIZATION, token(employee, Role.EMPLOYEE))).andExpect(status().isForbidden());
    mvc.perform(put("/api/knowledge-bases/{kb}/config/candidate", kb).header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), Role.KB_OPERATOR))
        .contentType("application/json").content(candidate(10, 4, "", "", "", null))).andExpect(status().isForbidden());
    var otherKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", otherKb, enterprise, "其他-" + otherKb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", otherKb, operator);
    publish(10, 4, "", "");
    mvc.perform(get("/api/knowledge-bases/{kb}/config", otherKb).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
        .andExpect(jsonPath("$.published").doesNotExist());
  }

  /** 发布与回滚在同一把知识库锁下发号：并发时后到的等待先到的提交，版本号连续，不会撞成"已存在"。 */
  @Test
  void rollbackWaitsForAConcurrentPublishInsteadOfCollidingOnTheVersionNumber() throws Exception {
    assertThat(publish(10, 4, "渠道一", "")).isEqualTo(1);
    int rev = revision(save(candidate(12, 4, "渠道二", "", "联调", null)).andExpect(status().isOk()));
    action("validate", rev).andExpect(status().isOk());
    var candidateId = repository.candidate(kb, false).orElseThrow().id();
    var locked = new java.util.concurrent.CountDownLatch(1);
    var proceed = new java.util.concurrent.CountDownLatch(1);
    var publishing = java.util.concurrent.CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
      repository.publish(kb, candidateId, operator);
      locked.countDown();
      try {
        proceed.await(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));
    assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    var rolling = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return mvc.perform(post("/api/knowledge-bases/{kb}/config/versions/{v}/rollback", kb, 1).header(HttpHeaders.AUTHORIZATION, token(operator, Role.KB_OPERATOR)))
            .andReturn().getResponse();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(300);
    assertThat(rolling.isDone()).as("回滚应等待未提交的发布").isFalse();
    proceed.countDown();
    publishing.get(10, java.util.concurrent.TimeUnit.SECONDS);
    var response = rolling.get(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(json.readTree(response.getContentAsString()).get("version").asInt()).isEqualTo(3);
    assertThat(jdbc.queryForList("SELECT version FROM kb_config WHERE kb_id = ? AND version IS NOT NULL ORDER BY version", Integer.class, kb))
        .containsExactly(1, 2, 3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_config WHERE kb_id = ? AND status = 'published'", Integer.class, kb)).isEqualTo(1);
  }
}
