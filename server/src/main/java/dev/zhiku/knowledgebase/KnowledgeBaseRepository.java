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
