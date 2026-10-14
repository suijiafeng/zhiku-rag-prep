package dev.zhiku.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.AccessScope;
import dev.zhiku.platform.RequestIdFilter;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only audit events. Detail must hold non-sensitive metadata only: never document text or questions. */
@Component
public class AuditLog {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AuditLog(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void record(AccessScope actor, String action, String target, String result, Map<String, ?> detail) {
    try {
      jdbc.update(
          "INSERT INTO audit_event (request_id, enterprise_id, actor_id, action, target, result, detail) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
          RequestIdFilter.current(), actor.enterpriseId(), actor.userId(), action, target, result, json.writeValueAsString(detail));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Audit detail is not serializable", e);
    }
  }
}
