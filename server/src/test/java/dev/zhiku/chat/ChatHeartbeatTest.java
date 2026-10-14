package dev.zhiku.chat;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestEmbeddingProvider;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 等待期间发送 SSE 注释作为心跳：客户端忽略注释，心跳不冒充内容。 */
@TestPropertySource(properties = "zhiku.chat.heartbeat-interval=PT0.05S")
class ChatHeartbeatTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestChatModel model;
  @Autowired TestEmbeddingProvider embeddings;

  @AfterEach
  void reset() {
    model.reset();
    embeddings.beforeEmbed = () -> {};
  }

  @Test
  void aSlowAnswerKeepsTheConnectionAliveWithComments() throws Exception {
    var enterprise = UUID.randomUUID();
    var user = UUID.randomUUID();
    var kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "心跳-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, user);
    embeddings.beforeEmbed = () -> {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    var started = mvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, Role.EMPLOYEE)))
            .contentType("application/json").content("{\"kbId\":\"" + kb + "\",\"question\":\"年假?\"}"))
        .andExpect(request().asyncStarted()).andReturn();
    started.getAsyncResult(10_000);
    var body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).contains("event:start");
    assertThat(body).contains(":keepalive");
  }
}
