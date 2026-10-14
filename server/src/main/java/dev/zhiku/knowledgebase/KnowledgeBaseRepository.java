package dev.zhiku.knowledgebase;

import dev.zhiku.auth.GrantLookup;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository implements GrantLookup {
  private final JdbcTemplate jdbc;

  public KnowledgeBaseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Set<UUID> grantedKnowledgeBases(UUID enterpriseId, UUID userId) {
    return new HashSet<>(jdbc.queryForList(
        "SELECT g.kb_id FROM kb_grant g JOIN knowledge_base kb ON kb.id = g.kb_id WHERE g.user_id = ? AND kb.enterprise_id = ?",
        UUID.class, userId, enterpriseId));
  }

  public List<KnowledgeBaseView> findAll(UUID enterpriseId, Set<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.query(
        "SELECT id, name FROM knowledge_base WHERE enterprise_id = ? AND id = ANY(?) ORDER BY name",
        (rs, i) -> new KnowledgeBaseView(rs.getObject("id", UUID.class), rs.getString("name")),
        enterpriseId, ids.toArray(UUID[]::new));
  }

  public record AdminView(UUID id, String name, int memberCount, java.time.Instant createdAt) {}

  public record GrantView(UUID userId, java.time.Instant grantedAt) {}

  public List<AdminView> findAllInEnterprise(UUID enterpriseId) {
    return jdbc.query("""
        SELECT kb.id, kb.name, kb.created_at, count(g.user_id) AS members
        FROM knowledge_base kb LEFT JOIN kb_grant g ON g.kb_id = kb.id
        WHERE kb.enterprise_id = ? GROUP BY kb.id ORDER BY kb.name
        """, (rs, i) -> new AdminView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("members"),
            rs.getTimestamp("created_at").toInstant()), enterpriseId);
  }

  public List<GrantView> grants(UUID kbId) {
    return jdbc.query("SELECT user_id, granted_at FROM kb_grant WHERE kb_id = ? ORDER BY granted_at, user_id",
        (rs, i) -> new GrantView(rs.getObject("user_id", UUID.class), rs.getTimestamp("granted_at").toInstant()), kbId);
  }

  public Optional<KnowledgeBaseRef> find(UUID id) {
    return jdbc.query("SELECT id, enterprise_id FROM knowledge_base WHERE id = ?",
        (rs, i) -> new KnowledgeBaseRef(rs.getObject("id", UUID.class), rs.getObject("enterprise_id", UUID.class)), id)
        .stream().findFirst();
  }

  public boolean nameTaken(UUID enterpriseId, String name) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM knowledge_base WHERE enterprise_id = ? AND name = ?)", Boolean.class, enterpriseId, name));
  }

  public void insert(UUID id, UUID enterpriseId, String name) {
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", id, enterpriseId, name);
  }

  public void grant(UUID kbId, UUID userId) {
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", kbId, userId);
  }

  public void revoke(UUID kbId, UUID userId) {
    jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kbId, userId);
  }
}
