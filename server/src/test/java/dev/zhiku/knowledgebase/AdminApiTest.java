package dev.zhiku.knowledgebase;

import static dev.zhiku.support.TestJwt.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AdminApiTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  final UUID enterprise = UUID.randomUUID();
  final UUID admin = UUID.randomUUID();
  final UUID member = UUID.randomUUID();
  UUID kb;
  UUID foreignKb;

  @BeforeEach
  void seed() {
    kb = UUID.randomUUID();
    foreignKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?), (?, ?, ?)",
        kb, enterprise, "管理-" + kb, foreignKb, UUID.randomUUID(), "外企业-" + foreignKb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, member);
  }

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, UUID user, Role... roles) {
    return req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)));
  }

  @Test
  void itAdminListsAllKnowledgeBasesOfTheEnterpriseWithMemberCounts() throws Exception {
    mvc.perform(as(get("/api/admin/knowledge-bases"), admin, Role.IT_ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(kb.toString()))
        .andExpect(jsonPath("$[0].memberCount").value(1));
  }

  @Test
  void itAdminListsGrantsOfOwnEnterpriseOnly() throws Exception {
    mvc.perform(as(get("/api/knowledge-bases/{kb}/grants", kb), admin, Role.IT_ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userId").value(member.toString()))
        .andExpect(jsonPath("$[0].grantedAt").isString());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/grants", foreignKb), admin, Role.IT_ADMIN)).andExpect(status().isForbidden());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/grants", UUID.randomUUID()), admin, Role.IT_ADMIN)).andExpect(status().isForbidden());
  }

  @Test
  void nonAdminsCannotUseAdminListings() throws Exception {
    mvc.perform(as(get("/api/admin/knowledge-bases"), member, Role.KB_OPERATOR, Role.EMPLOYEE)).andExpect(status().isForbidden());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/grants", kb), member, Role.KB_OPERATOR)).andExpect(status().isForbidden());
  }

  @Test
  void meReflectsTheVerifiedTokenAndCurrentGrants() throws Exception {
    mvc.perform(as(get("/api/me"), member, Role.EMPLOYEE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(member.toString()))
        .andExpect(jsonPath("$.enterpriseId").value(enterprise.toString()))
        .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"))
        .andExpect(jsonPath("$.knowledgeBaseIds[0]").value(kb.toString()));
  }

  @Test
  void roleMatrixComesFromTheServerPolicy() throws Exception {
    mvc.perform(as(get("/api/policy/roles"), member, Role.EMPLOYEE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles.length()").value(3))
        .andExpect(jsonPath("$.actions.length()").value(4))
        .andExpect(jsonPath("$.rules.EMPLOYEE.MANAGE_DOCUMENTS").value("NEVER"))
        .andExpect(jsonPath("$.rules.KB_OPERATOR.MANAGE_DOCUMENTS").value("ALLOWED"))
        .andExpect(jsonPath("$.rules.IT_ADMIN.MANAGE_DOCUMENTS").value("REQUIRES_EXPLICIT_GRANT"));
  }
}
