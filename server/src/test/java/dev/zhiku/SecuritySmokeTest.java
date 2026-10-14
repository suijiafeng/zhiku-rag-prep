package dev.zhiku;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecuritySmokeTest {
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
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.requestId").isString());
  }

  @Test
  void anonymousWriteWithoutCsrfIsRejectedBeforeAuthentication() throws Exception {
    // CsrfFilter answers through the access-denied handler, so the contract shape still applies.
    mvc.perform(post("/api/chat").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.requestId").isString());
  }

  @Test
  void anonymousWriteWithCsrfIsUnauthenticated() throws Exception {
    mvc.perform(post("/api/chat").with(csrf()).contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void authenticatedWithoutGrantIsForbidden() throws Exception {
    mvc.perform(get("/api/knowledge-bases").with(user("fixture-user")))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.requestId").isString());
  }

  @Test
  void authenticatedWriteWithCsrfStillForbidden() throws Exception {
    mvc.perform(post("/api/chat").with(user("fixture-user")).with(csrf()).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void unknownPathDoesNotRevealExistence() throws Exception {
    mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/does-not-exist").with(user("fixture-user"))).andExpect(status().isForbidden());
  }
}
