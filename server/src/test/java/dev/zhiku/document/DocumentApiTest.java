package dev.zhiku.document;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DocumentApiTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  final UUID employee = UUID.randomUUID();
  final UUID outsider = UUID.randomUUID();
  UUID kb;
  UUID otherKb;

  @BeforeEach
  void seed() {
    kb = UUID.randomUUID();
    otherKb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?), (?, ?, ?)",
        kb, enterprise, "制度-" + kb, otherKb, enterprise, "其他-" + otherKb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?), (?, ?)", kb, operator, kb, employee, otherKb, operator);
  }

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, UUID user, Role... roles) {
    return req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)));
  }

  ResultActions postJson(String path, String body, UUID user, Role... roles) throws Exception {
    return mvc.perform(as(post(path), user, roles).contentType("application/json").content(body));
  }

  UUID id(ResultActions r) throws Exception {
    return UUID.fromString(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asText());
  }

  UUID createDocument(UUID kbId) throws Exception {
    return id(postJson("/api/knowledge-bases/" + kbId + "/documents", "{\"title\":\"员工手册\"}", operator, Role.KB_OPERATOR).andExpect(status().isCreated()));
  }

  UUID createVersion(UUID doc, String label, String from, String until) throws Exception {
    var body = "{\"versionLabel\":\"" + label + "\",\"effectiveFrom\":\"" + from + "\"" + (until == null ? "" : ",\"effectiveUntil\":\"" + until + "\"") + "}";
    return id(postJson("/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions", body, operator, Role.KB_OPERATOR).andExpect(status().isCreated()));
  }

  void approveAndPublish(UUID doc, UUID version) throws Exception {
    var base = "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + version;
    mvc.perform(as(post(base + "/approve"), operator, Role.KB_OPERATOR)).andExpect(status().isNoContent());
    mvc.perform(as(post(base + "/publish"), operator, Role.KB_OPERATOR)).andExpect(status().isNoContent());
  }

  void markIndexed(UUID version) {
    jdbc.update("UPDATE content_version SET index_status = 'ready' WHERE id = ?", version);
  }

  ResultActions eligibility(Instant at) throws Exception {
    return mvc.perform(as(get("/api/knowledge-bases/" + kb + "/eligibility").param("at", at.toString()), employee, Role.EMPLOYEE));
  }

  @Test
  void listingRequiresGrantAndRole() throws Exception {
    createDocument(kb);
    mvc.perform(as(get("/api/knowledge-bases/" + kb + "/documents"), employee, Role.EMPLOYEE))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("员工手册"));
    mvc.perform(as(get("/api/knowledge-bases/" + kb + "/documents"), outsider, Role.EMPLOYEE, Role.KB_OPERATOR)).andExpect(status().isForbidden());
    mvc.perform(as(get("/api/knowledge-bases/" + kb + "/documents"), employee)).andExpect(status().isForbidden());
    mvc.perform(get("/api/knowledge-bases/" + kb + "/documents").header(HttpHeaders.AUTHORIZATION,
        bearer(TestJwt.token(UUID.randomUUID(), employee, Role.EMPLOYEE)))).andExpect(status().isForbidden());
  }

  @Test
  void onlyGrantedOperatorsManageDocuments() throws Exception {
    postJson("/api/knowledge-bases/" + kb + "/documents", "{\"title\":\"x\"}", employee, Role.EMPLOYEE).andExpect(status().isForbidden());
    postJson("/api/knowledge-bases/" + kb + "/documents", "{\"title\":\"x\"}", outsider, Role.KB_OPERATOR).andExpect(status().isForbidden());
    postJson("/api/knowledge-bases/" + kb + "/documents", "{\"title\":\"x\"}", employee, Role.IT_ADMIN).andExpect(status().isForbidden());
    createDocument(kb);
  }

  @Test
  void businessDatesUseTheConfiguredZone() throws Exception {
    var doc = createDocument(kb);
    var version = createVersion(doc, "v1", "2026-01-01", "2026-07-01");
    var from = jdbc.queryForObject("SELECT effective_from FROM content_version WHERE id = ?", Timestamp.class, version).toInstant();
    var until = jdbc.queryForObject("SELECT effective_until FROM content_version WHERE id = ?", Timestamp.class, version).toInstant();
    assertThat(from).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"));
    assertThat(until).isEqualTo(Instant.parse("2026-06-30T16:00:00Z"));
  }

  @Test
  void rejectsInvalidIntervalsAndDuplicateLabels() throws Exception {
    var doc = createDocument(kb);
    var path = "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions";
    postJson(path, "{\"versionLabel\":\"v1\",\"effectiveFrom\":\"2026-02-01\",\"effectiveUntil\":\"2026-02-01\"}", operator, Role.KB_OPERATOR)
        .andExpect(status().isUnprocessableEntity());
    createVersion(doc, "v1", "2026-01-01", null);
    postJson(path, "{\"versionLabel\":\"v1\",\"effectiveFrom\":\"2026-03-01\"}", operator, Role.KB_OPERATOR).andExpect(status().isConflict());
  }

  @Test
  void publishRequiresApprovalAndIsAudited() throws Exception {
    var doc = createDocument(kb);
    var version = createVersion(doc, "v1", "2026-01-01", null);
    var base = "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + version;
    mvc.perform(as(post(base + "/publish"), operator, Role.KB_OPERATOR)).andExpect(status().isConflict());
    approveAndPublish(doc, version);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target = ? AND action IN ('VERSION_APPROVE','VERSION_PUBLISH')",
        Integer.class, "version:" + version)).isEqualTo(2);
  }

  @Test
  void documentsAreScopedToTheirKnowledgeBase() throws Exception {
    var foreignDoc = createDocument(otherKb);
    postJson("/api/knowledge-bases/" + kb + "/documents/" + foreignDoc + "/versions", "{\"versionLabel\":\"v1\",\"effectiveFrom\":\"2026-01-01\"}", operator, Role.KB_OPERATOR)
        .andExpect(status().isForbidden());
  }

  @Test
  void currentAndFutureVersionsCoexistAndSwitchAtTheBoundary() throws Exception {
    var doc = createDocument(kb);
    var current = createVersion(doc, "2025", "2025-01-01", "2026-01-01");
    var future = createVersion(doc, "2026", "2026-01-01", null);
    approveAndPublish(doc, current);
    approveAndPublish(doc, future);
    markIndexed(current);
    markIndexed(future);
    eligibility(Instant.parse("2025-12-31T15:59:59Z"))
        .andExpect(jsonPath("$[0].outcome").value("ELIGIBLE")).andExpect(jsonPath("$[0].versionId").value(current.toString()));
    eligibility(Instant.parse("2025-12-31T16:00:00Z"))
        .andExpect(jsonPath("$[0].outcome").value("ELIGIBLE")).andExpect(jsonPath("$[0].versionId").value(future.toString()));
  }

  @Test
  void unindexedReplacementDoesNotFallBackToTheOldVersion() throws Exception {
    var doc = createDocument(kb);
    var old = createVersion(doc, "old", "2025-01-01", "2026-01-01");
    var replacement = createVersion(doc, "new", "2026-01-01", null);
    approveAndPublish(doc, old);
    approveAndPublish(doc, replacement);
    markIndexed(old);
    eligibility(Instant.parse("2026-03-01T00:00:00Z"))
        .andExpect(jsonPath("$[0].outcome").value("INDEX_NOT_READY")).andExpect(jsonPath("$[0].versionId").doesNotExist());
  }

  @Test
  void unpublishedWithdrawnAndOverlappingVersionsFailClosed() throws Exception {
    var doc = createDocument(kb);
    var draft = createVersion(doc, "draft", "2025-01-01", null);
    markIndexed(draft);
    eligibility(Instant.parse("2026-03-01T00:00:00Z")).andExpect(jsonPath("$[0].outcome").value("NO_EVIDENCE"));

    var a = createVersion(doc, "a", "2025-01-01", null);
    var b = createVersion(doc, "b", "2025-06-01", null);
    approveAndPublish(doc, a);
    approveAndPublish(doc, b);
    markIndexed(a);
    markIndexed(b);
    eligibility(Instant.parse("2026-03-01T00:00:00Z")).andExpect(jsonPath("$[0].outcome").value("CONFLICT"));

    mvc.perform(as(post("/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + b + "/withdraw"), operator, Role.KB_OPERATOR))
        .andExpect(status().isNoContent());
    eligibility(Instant.parse("2026-03-01T00:00:00Z"))
        .andExpect(jsonPath("$[0].outcome").value("ELIGIBLE")).andExpect(jsonPath("$[0].versionId").value(a.toString()));
  }
}
