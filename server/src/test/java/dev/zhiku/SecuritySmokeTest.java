package dev.zhiku;

import static dev.zhiku.support.TestJwt.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

class SecuritySmokeTest extends IntegrationTest {
  @Autowired MockMvc mvc;

  @Test
  void publicStatusHasNoModel() throws Exception {
    mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.modelEnabled").value(false));
  }

  @Test
  void anonymousBusinessReadIsUnauthenticated() throws Exception {
    mvc.perform(get("/api/knowledge-bases"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.requestId").isString());
  }

  @Test
  void anonymousWriteIsUnauthenticatedWithoutSessionOrCsrf() throws Exception {
    mvc.perform(post("/api/chat").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void requestIdHeaderMatchesErrorBody() throws Exception {
    var result = mvc.perform(get("/api/knowledge-bases")).andReturn();
    var body = result.getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(body).contains(result.getResponse().getHeader("X-Request-Id"));
  }

  @Test
  void nonApiPathsStayDenied() throws Exception {
    var token = TestJwt.token(UUID.randomUUID(), UUID.randomUUID(), Role.IT_ADMIN);
    mvc.perform(get("/actuator").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isForbidden());
  }

  @Test
  void unknownApiPathNeedsAuthenticationFirst() throws Exception {
    mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    var token = TestJwt.token(UUID.randomUUID(), UUID.randomUUID(), Role.EMPLOYEE);
    mvc.perform(get("/api/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
