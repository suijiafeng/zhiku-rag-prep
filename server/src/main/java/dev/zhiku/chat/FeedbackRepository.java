package dev.zhiku.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class FeedbackRepository {
  record FeedbackView(String rating, String reason, String comment, int version, String status, Instant updatedAt) {}

  record OwnedMessage(UUID messageId, UUID userId, UUID enterpriseId, UUID kbId) {}

  record RecordRow(UUID messageId, Instant createdAt, UUID userId, String question, int citationCount, String rating,
      String reason, String comment, String status, String rootCause) {}

  private final JdbcTemplate jdbc;

  FeedbackRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static FeedbackView view(ResultSet rs, int i) throws SQLException {
    return new FeedbackView(rs.getString("rating"), rs.getString("reason"), rs.getString("comment"), rs.getInt("version"),
        rs.getString("status"), rs.getTimestamp("updated_at").toInstant());
  }

  Optional<OwnedMessage> owner(UUID messageId) {
    return jdbc.query("SELECT m.id, c.user_id, c.enterprise_id, c.kb_id FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE m.id = ?",
        (rs, i) -> new OwnedMessage(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getObject("enterprise_id", UUID.class), rs.getObject("kb_id", UUID.class)), messageId).stream().findFirst();
  }

  FeedbackView upsert(UUID messageId, UUID userId, String rating, String reason, String comment) {
    var view = jdbc.queryForObject("""
        INSERT INTO feedback (message_id, user_id, rating, reason, comment) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (message_id) DO UPDATE SET rating = EXCLUDED.rating, reason = EXCLUDED.reason, comment = EXCLUDED.comment,
          version = feedback.version + 1, updated_at = now()
        RETURNING rating, reason, comment, version, status, updated_at
        """, FeedbackRepository::view, messageId, userId, rating, reason, comment);
    jdbc.update("INSERT INTO feedback_history (message_id, version, rating, reason, comment) VALUES (?, ?, ?, ?, ?)",
        messageId, view.version(), rating, reason, comment);
    return view;
  }

  Map<UUID, FeedbackView> forConversation(UUID conversationId) {
    var out = new HashMap<UUID, FeedbackView>();
    jdbc.query("SELECT f.message_id, f.rating, f.reason, f.comment, f.version, f.status, f.updated_at FROM feedback f JOIN message m ON m.id = f.message_id WHERE m.conversation_id = ?",
        rs -> {
          out.put(rs.getObject("message_id", UUID.class), view(rs, 0));
        }, conversationId);
    return out;
  }

  List<RecordRow> records(UUID kbId, String filter) {
    var where = switch (filter) {
      case "up" -> " AND f.rating = 'up'";
      case "down" -> " AND f.rating = 'down'";
      case "none" -> " AND f.message_id IS NULL";
      default -> "";
    };
    return jdbc.query("""
        SELECT m.id, m.created_at, c.user_id, m.question,
               (SELECT count(*) FROM message_source s WHERE s.message_id = m.id) AS citations,
               f.rating, f.reason, f.comment, f.status, f.root_cause
        FROM message m JOIN conversation c ON c.id = m.conversation_id LEFT JOIN feedback f ON f.message_id = m.id
        WHERE c.kb_id = ?""" + where + " ORDER BY m.created_at DESC, m.id LIMIT 200",
        (rs, i) -> new RecordRow(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getObject("user_id", UUID.class),
            rs.getString("question"), rs.getInt("citations"), rs.getString("rating"), rs.getString("reason"), rs.getString("comment"),
            rs.getString("status"), rs.getString("root_cause")), kbId);
  }

  Map<String, Integer> stats(UUID kbId, Instant since) {
    var out = new HashMap<String, Integer>();
    jdbc.query("""
        SELECT coalesce(f.rating, 'unrated') AS k, count(*) AS n FROM message m JOIN conversation c ON c.id = m.conversation_id
        LEFT JOIN feedback f ON f.message_id = m.id WHERE c.kb_id = ? AND m.created_at >= ? GROUP BY 1
        UNION ALL
        SELECT 'reason:' || f.reason, count(*) FROM feedback f JOIN message m ON m.id = f.message_id JOIN conversation c ON c.id = m.conversation_id
        WHERE c.kb_id = ? AND m.created_at >= ? AND f.rating = 'down' GROUP BY f.reason
        """, rs -> {
          out.put(rs.getString(1), rs.getInt(2));
        }, kbId, java.sql.Timestamp.from(since), kbId, java.sql.Timestamp.from(since));
    return out;
  }

  int updateStatus(UUID kbId, UUID messageId, String status, String rootCause, UUID handledBy) {
    return jdbc.update("""
        UPDATE feedback f SET status = ?, root_cause = ?, handled_by = ?, updated_at = now()
        FROM message m JOIN conversation c ON c.id = m.conversation_id
        WHERE f.message_id = ? AND m.id = f.message_id AND c.kb_id = ?
        """, status, rootCause, handledBy, messageId, kbId);
  }
}
