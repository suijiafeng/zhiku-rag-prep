package dev.zhiku.knowledgebase;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class KnowledgeBaseApiTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  final UUID enterprise = UUID.randomUUID();
  final UUID admin = UUID.randomUUID();
  final UUID employee = UUID.randomUUID();

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, UUID ent, UUID user, Role... roles) {
    return req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(ent, user, roles)));
  }

  UUID createKb(String name) throws Exception {
    var body = mvc.perform(as(post("/api/knowledge-bases"), enterprise, admin, Role.IT_ADMIN)
            .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return UUID.fromString(json.readTree(body).get("id").asText());
  }

  @Test
  void rejectsInvalidTokens() throws Exception {
    var wrongKey = TestJwt.token(enterprise, employee, b -> {}, "x".repeat(48), Role.EMPLOYEE);
    var expired = TestJwt.token(enterprise, employee, b -> b.expirationTime(new Date(System.currentTimeMillis() - 600_000)), TestJwt.SECRET, Role.EMPLOYEE);
    var wrongAudience = TestJwt.token(enterprise, employee, b -> b.audience("other"), TestJwt.SECRET, Role.EMPLOYEE);
    var noExpiry = TestJwt.token(enterprise, employee, b -> b.expirationTime(null), TestJwt.SECRET, Role.EMPLOYEE);
    var badSubject = TestJwt.token(enterprise, employee, b -> b.subject("not-a-uuid"), TestJwt.SECRET, Role.EMPLOYEE);
    for (var token : new String[] {wrongKey, expired, wrongAudience, noExpiry, badSubject}) {
      mvc.perform(get("/api/knowledge-bases").header(HttpHeaders.AUTHORIZATION, bearer(token)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
  }

  @Test
  void grantAndRevokeTakeEffectImmediately() throws Exception {
    var kb = createKb("制度库-" + UUID.randomUUID());
    mvc.perform(as(get("/api/knowledge-bases"), enterprise, admin, Role.IT_ADMIN)).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(get("/api/knowledge-bases"), enterprise, employee, Role.EMPLOYEE)).andExpect(jsonPath("$.length()").value(0));

    mvc.perform(as(put("/api/knowledge-bases/{kb}/grants/{user}", kb, employee), enterprise, admin, Role.IT_ADMIN)).andExpect(status().isNoContent());
    mvc.perform(as(get("/api/knowledge-bases"), enterprise, employee, Role.EMPLOYEE))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(kb.toString()));

    mvc.perform(as(delete("/api/knowledge-bases/{kb}/grants/{user}", kb, employee), enterprise, admin, Role.IT_ADMIN)).andExpect(status().isNoContent());
    mvc.perform(as(get("/api/knowledge-bases"), enterprise, employee, Role.EMPLOYEE)).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void onlyItAdminManagesKnowledgeBasesAndGrants() throws Exception {
    var kb = createKb("运营库-" + UUID.randomUUID());
    mvc.perform(as(post("/api/knowledge-bases"), enterprise, employee, Role.EMPLOYEE, Role.KB_OPERATOR)
            .contentType("application/json").content("{\"name\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(as(put("/api/knowledge-bases/{kb}/grants/{user}", kb, employee), enterprise, employee, Role.KB_OPERATOR))
        .andExpect(status().isForbidden());
  }

  @Test
  void crossEnterpriseAndUnknownKnowledgeBasesAreIndistinguishable() throws Exception {
    var kb = createKb("隔离库-" + UUID.randomUUID());
    var otherEnterprise = UUID.randomUUID();
    var foreign = mvc.perform(as(put("/api/knowledge-bases/{kb}/grants/{user}", kb, employee), otherEnterprise, UUID.randomUUID(), Role.IT_ADMIN))
        .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
    var unknown = mvc.perform(as(put("/api/knowledge-bases/{kb}/grants/{user}", UUID.randomUUID(), employee), otherEnterprise, UUID.randomUUID(), Role.IT_ADMIN))
        .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(foreign).get("code")).isEqualTo(json.readTree(unknown).get("code"));
    assertThat(json.readTree(foreign).get("message")).isEqualTo(json.readTree(unknown).get("message"));
  }

  @Test
  void enterpriseInRequestBodyIsIgnored() throws Exception {
    var otherEnterprise = UUID.randomUUID();
    var body = mvc.perform(as(post("/api/knowledge-bases"), enterprise, admin, Role.IT_ADMIN)
            .contentType("application/json")
            .content("{\"name\":\"伪造-" + UUID.randomUUID() + "\",\"enterpriseId\":\"" + otherEnterprise + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var kb = UUID.fromString(json.readTree(body).get("id").asText());
    assertThat(jdbc.queryForObject("SELECT enterprise_id FROM knowledge_base WHERE id = ?", UUID.class, kb)).isEqualTo(enterprise);
    mvc.perform(as(put("/api/knowledge-bases/{kb}/grants/{user}", kb, employee), otherEnterprise, UUID.randomUUID(), Role.IT_ADMIN))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidInputIsUnprocessable() throws Exception {
    mvc.perform(as(post("/api/knowledge-bases"), enterprise, admin, Role.IT_ADMIN).contentType("application/json").content("{\"name\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION"));
    mvc.perform(as(put("/api/knowledge-bases/not-a-uuid/grants/{user}", employee), enterprise, admin, Role.IT_ADMIN))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void grantChangesAreAuditedAppendOnly() throws Exception {
    var kb = createKb("审计库-" + UUID.randomUUID());
    mvc.perform(as(put("/api/knowledge-bases/{kb}/grants/{user}", kb, employee), enterprise, admin, Role.IT_ADMIN)).andExpect(status().isNoContent());
    var count = jdbc.queryForObject(
        "SELECT count(*) FROM audit_event WHERE action = 'KB_GRANT' AND target = ? AND actor_id = ? AND request_id <> ''", Integer.class, "kb:" + kb + "/user:" + employee, admin);
    assertThat(count).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET result = 'x' WHERE target = ?", "kb:" + kb + "/user:" + employee))
        .hasMessageContaining("append-only");
  }
}
