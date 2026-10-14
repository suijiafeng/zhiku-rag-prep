package dev.zhiku.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class FeedbackRepository {
  record FeedbackView(String rating, String reason, String comment, int version, String status, Instant updatedAt) {}

  record OwnedMessage(UUID messageId, UUID userId, UUID enterpriseId, UUID kbId, String status) {}

  record RecordRow(UUID messageId, Instant createdAt, UUID userId, String question, int citationCount, String rating,
      String reason, String comment, String status, String rootCause, String resolution, UUID verificationCaseId, String outcome,
      Boolean sourceAvailable) {}

  record HandlingView(String status, String rootCause, String resolution, UUID verificationCaseId, UUID changedBy, Instant changedAt,
      Integer feedbackVersion) {}

  /**
   * 与历史视图规则相同：已完成回答仅当其自身及生成它所依据的每个较早轮次满足以下条件时可用：
   * 每个来源文档存在且未删除、其版本未撤回、chunk 仍
   * 存在，并且属于该会话的知识库。对非已完成回答的轮次返回 null。
   */
  private static final String SOURCE_AVAILABLE = """
      CASE WHEN m.status <> 'complete' THEN NULL ELSE NOT EXISTS (
        SELECT 1 FROM (SELECT m.id AS mid UNION ALL SELECT mc.depends_on FROM message_context mc WHERE mc.message_id = m.id) dep
        WHERE NOT EXISTS (SELECT 1 FROM message_source s WHERE s.message_id = dep.mid)
           OR EXISTS (
             SELECT 1 FROM message_source s
             LEFT JOIN content_version v ON v.id = s.version_id
             LEFT JOIN document d ON d.id = v.document_id AND d.deleted_at IS NULL
             LEFT JOIN chunk ch ON ch.id = s.chunk_id AND ch.version_id = s.version_id
             WHERE s.message_id = dep.mid AND (d.id IS NULL OR v.withdrawn_at IS NOT NULL OR ch.id IS NULL OR d.kb_id <> c.kb_id)))
      END""";

  private final JdbcTemplate jdbc;

  FeedbackRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static FeedbackView view(ResultSet rs, int i) throws SQLException {
    return new FeedbackView(rs.getString("rating"), rs.getString("reason"), rs.getString("comment"), rs.getInt("version"),
        rs.getString("status"), rs.getTimestamp("updated_at").toInstant());
  }

  Optional<OwnedMessage> owner(UUID messageId) {
    return jdbc.query("SELECT m.id, c.user_id, c.enterprise_id, c.kb_id, m.status FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE m.id = ?",
        (rs, i) -> new OwnedMessage(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getObject("enterprise_id", UUID.class), rs.getObject("kb_id", UUID.class), rs.getString("status")), messageId).stream().findFirst();
  }

  /**
   * 保存提问者的反馈。未改变的重复提交不产生任何变化。改变后的反馈获得新版本，
   * 若此前已处理则重新打开：旧结论针对的是上一版本（C16）。
   */
  FeedbackView upsert(UUID messageId, UUID userId, String rating, String reason, String comment) {
    record Current(FeedbackView view, boolean handled) {}
    var current = jdbc.query("SELECT rating, reason, comment, version, status, updated_at, root_cause, resolution FROM feedback WHERE message_id = ? FOR UPDATE",
        (rs, i) -> new Current(view(rs, i), !"open".equals(rs.getString("status")) || rs.getString("root_cause") != null || rs.getString("resolution") != null),
        messageId).stream().findFirst();
    if (current.isPresent() && Objects.equals(current.get().view().rating(), rating) && Objects.equals(current.get().view().reason(), reason)
        && Objects.equals(current.get().view().comment(), comment)) {
      return current.get().view();
    }
    var view = jdbc.queryForObject("""
        INSERT INTO feedback (message_id, user_id, rating, reason, comment) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (message_id) DO UPDATE SET rating = EXCLUDED.rating, reason = EXCLUDED.reason, comment = EXCLUDED.comment,
          version = feedback.version + 1, status = 'open', root_cause = NULL, resolution = NULL, verification_case_id = NULL,
          handled_by = NULL, updated_at = now()
        RETURNING rating, reason, comment, version, status, updated_at
        """, FeedbackRepository::view, messageId, userId, rating, reason, comment);
    jdbc.update("INSERT INTO feedback_history (message_id, version, rating, reason, comment) VALUES (?, ?, ?, ?, ?)",
        messageId, view.version(), rating, reason, comment);
    if (current.isPresent() && current.get().handled()) {
      jdbc.update("INSERT INTO feedback_handling (message_id, status, changed_by, feedback_version) VALUES (?, 'open', ?, ?)",
          messageId, userId, view.version());
    }
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
    var sql = """
        SELECT m.id, m.created_at, c.user_id, m.question,
               (SELECT count(*) FROM message_source s WHERE s.message_id = m.id) AS citations,
               f.rating, f.reason, f.comment, f.status, f.root_cause, f.resolution, f.verification_case_id, m.status AS outcome,
               %s AS source_available
        FROM message m JOIN conversation c ON c.id = m.conversation_id LEFT JOIN feedback f ON f.message_id = m.id
        WHERE c.kb_id = ?""".formatted(SOURCE_AVAILABLE) + where + " ORDER BY m.created_at DESC, m.id LIMIT 200";
    return jdbc.query(sql,
        (rs, i) -> new RecordRow(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getObject("user_id", UUID.class),
            rs.getString("question"), rs.getInt("citations"), rs.getString("rating"), rs.getString("reason"), rs.getString("comment"),
            rs.getString("status"), rs.getString("root_cause"), rs.getString("resolution"), rs.getObject("verification_case_id", UUID.class),
            rs.getString("outcome"), (Boolean) rs.getObject("source_available")), kbId);
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

  boolean feedbackInKnowledgeBase(UUID kbId, UUID messageId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM feedback f JOIN message m ON m.id = f.message_id JOIN conversation c ON c.id = m.conversation_id
                       WHERE f.message_id = ? AND c.kb_id = ?)
        """, Boolean.class, messageId, kbId));
  }

  void updateStatus(UUID kbId, UUID messageId, String status, String rootCause, String resolution, UUID verificationCaseId, UUID handledBy) {
    jdbc.update("""
        UPDATE feedback f SET status = ?, root_cause = ?, resolution = ?, verification_case_id = ?, handled_by = ?, updated_at = now()
        FROM message m JOIN conversation c ON c.id = m.conversation_id
        WHERE f.message_id = ? AND m.id = f.message_id AND c.kb_id = ?
        """, status, rootCause, resolution, verificationCaseId, handledBy, messageId, kbId);
    jdbc.update("""
        INSERT INTO feedback_handling (message_id, status, root_cause, resolution, verification_case_id, changed_by, feedback_version)
        SELECT ?, ?, ?, ?, ?, ?, version FROM feedback WHERE message_id = ?
        """, messageId, status, rootCause, resolution, verificationCaseId, handledBy, messageId);
  }

  /** 仅限本知识库内反馈的处理变更；其他知识库的 id 不返回任何内容。 */
  List<HandlingView> handlingHistory(UUID kbId, UUID messageId) {
    return jdbc.query("""
        SELECT h.status, h.root_cause, h.resolution, h.verification_case_id, h.changed_by, h.changed_at, h.feedback_version
        FROM feedback_handling h JOIN message m ON m.id = h.message_id JOIN conversation c ON c.id = m.conversation_id
        WHERE h.message_id = ? AND c.kb_id = ? ORDER BY h.id
        """, (rs, i) -> new HandlingView(rs.getString("status"), rs.getString("root_cause"), rs.getString("resolution"),
            rs.getObject("verification_case_id", UUID.class), rs.getObject("changed_by", UUID.class), rs.getTimestamp("changed_at").toInstant(),
            (Integer) rs.getObject("feedback_version")),
        messageId, kbId);
  }
}
