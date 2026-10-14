package dev.zhiku.chat;

import dev.zhiku.retrieval.Retrieval.Candidate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ChatRepository {
  record ConversationRow(UUID id, UUID kbId, String title, Instant updatedAt) {}

  record MessageRow(UUID id, String question, String answer, Instant createdAt, String status, String errorCode) {}

  record RequestRow(UUID kbId, String digest, UUID conversationId, UUID messageId, String messageStatus, Instant acceptedAt) {}

  record SourceRow(UUID messageId, int ordinal, UUID versionId, UUID chunkId, UUID documentId, String title, UUID kbId,
      boolean withdrawn, String chunkText) {}

  private final JdbcTemplate jdbc;

  ChatRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<ConversationRow> conversation(UUID id, UUID enterpriseId, UUID userId) {
    return jdbc.query("SELECT id, kb_id, title, updated_at FROM conversation WHERE id = ? AND enterprise_id = ? AND user_id = ?",
        (rs, i) -> new ConversationRow(rs.getObject("id", UUID.class), rs.getObject("kb_id", UUID.class), rs.getString("title"),
            rs.getTimestamp("updated_at").toInstant()), id, enterpriseId, userId).stream().findFirst();
  }

  List<ConversationRow> conversations(UUID enterpriseId, UUID userId) {
    return jdbc.query("SELECT id, kb_id, title, updated_at FROM conversation WHERE enterprise_id = ? AND user_id = ? ORDER BY updated_at DESC LIMIT 100",
        (rs, i) -> new ConversationRow(rs.getObject("id", UUID.class), rs.getObject("kb_id", UUID.class), rs.getString("title"),
            rs.getTimestamp("updated_at").toInstant()), enterpriseId, userId);
  }

  UUID createConversation(UUID enterpriseId, UUID userId, UUID kbId, String title) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO conversation (id, enterprise_id, user_id, kb_id, title) VALUES (?, ?, ?, ?, ?)", id, enterpriseId, userId, kbId, title);
    return id;
  }

  UUID saveMessage(UUID conversationId, String question, String answer, String model, List<Candidate> sources,
      java.util.Collection<UUID> dependsOn) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model) VALUES (?, ?, ?, ?, ?)", id, conversationId, question, answer, model);
    for (var earlier : dependsOn) {
      jdbc.update("INSERT INTO message_context (message_id, depends_on) VALUES (?, ?)", id, earlier);
    }
    for (int i = 0; i < sources.size(); i++) {
      jdbc.update("INSERT INTO message_source (message_id, ordinal, version_id, chunk_id) VALUES (?, ?, ?, ?)",
          id, i + 1, sources.get(i).versionId(), sources.get(i).chunkId());
    }
    jdbc.update("UPDATE conversation SET updated_at = now() WHERE id = ?", conversationId);
    return id;
  }

  List<MessageRow> messages(UUID conversationId) {
    return jdbc.query("SELECT id, question, answer, created_at, status, error_code FROM message WHERE conversation_id = ? ORDER BY created_at, id",
        (rs, i) -> new MessageRow(rs.getObject("id", UUID.class), rs.getString("question"), rs.getString("answer"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("status"), rs.getString("error_code")), conversationId);
  }

  /** 保存未完成的轮次：只存问题和结果，绝不保存草稿正文或来源。 */
  UUID saveOutcome(UUID conversationId, String question, String status, String errorCode, String model) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO message (id, conversation_id, question, answer, model, status, error_code) VALUES (?, ?, ?, NULL, ?, ?, ?)",
        id, conversationId, question, model, status, errorCode);
    jdbc.update("UPDATE conversation SET updated_at = now() WHERE id = ?", conversationId);
    return id;
  }

  int turnCount(UUID conversationId) {
    return jdbc.queryForObject("SELECT count(*) FROM message WHERE conversation_id = ?", Integer.class, conversationId);
  }

  /** 会话中每条消息所依赖的较早轮次（已是闭包）。 */
  java.util.Map<UUID, java.util.Set<UUID>> context(UUID conversationId) {
    var out = new java.util.HashMap<UUID, java.util.Set<UUID>>();
    jdbc.query("SELECT mc.message_id, mc.depends_on FROM message_context mc JOIN message m ON m.id = mc.message_id WHERE m.conversation_id = ?",
        rs -> {
          out.computeIfAbsent(rs.getObject("message_id", UUID.class), k -> new java.util.HashSet<>()).add(rs.getObject("depends_on", UUID.class));
        }, conversationId);
    return out;
  }

  /** 关联到当前状态的来源；关联缺失（文档已删除或已重建索引）时 id 为 null。 */
  List<SourceRow> sources(UUID conversationId) {
    return jdbc.query("""
        SELECT s.message_id, s.ordinal, s.version_id, s.chunk_id, d.id AS document_id, d.title, d.kb_id,
               v.withdrawn_at IS NOT NULL AS withdrawn, c.text AS chunk_text
        FROM message_source s
        JOIN message m ON m.id = s.message_id
        LEFT JOIN content_version v ON v.id = s.version_id
        LEFT JOIN document d ON d.id = v.document_id AND d.deleted_at IS NULL
        LEFT JOIN chunk c ON c.id = s.chunk_id AND c.version_id = s.version_id
        WHERE m.conversation_id = ?
        ORDER BY s.message_id, s.ordinal
        """, (rs, i) -> new SourceRow(rs.getObject("message_id", UUID.class), rs.getInt("ordinal"), rs.getObject("version_id", UUID.class),
            rs.getObject("chunk_id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("title"),
            rs.getObject("kb_id", UUID.class), rs.getBoolean("withdrawn"), rs.getString("chunk_text")), conversationId);
  }

  /** 每个用户的客户端请求 id 只接受一次；已被接受过时返回 false。 */
  boolean accept(UUID enterpriseId, UUID userId, UUID clientRequestId, UUID kbId, String digest) {
    return jdbc.update("INSERT INTO chat_request (enterprise_id, user_id, client_request_id, kb_id, digest) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        enterpriseId, userId, clientRequestId, kbId, digest) == 1;
  }

  void release(UUID enterpriseId, UUID userId, UUID clientRequestId) {
    jdbc.update("DELETE FROM chat_request WHERE enterprise_id = ? AND user_id = ? AND client_request_id = ? AND message_id IS NULL",
        enterpriseId, userId, clientRequestId);
  }

  void linkRequest(UUID enterpriseId, UUID userId, UUID clientRequestId, UUID messageId) {
    jdbc.update("UPDATE chat_request SET message_id = ? WHERE enterprise_id = ? AND user_id = ? AND client_request_id = ?",
        messageId, enterpriseId, userId, clientRequestId);
  }

  /** 分批删除早于 before 的请求去重记录：过期后同一客户端请求编号可再次受理。 */
  int deleteRequestsAcceptedBefore(Instant before, int limit) {
    return jdbc.update("""
        DELETE FROM chat_request r USING (SELECT enterprise_id, user_id, client_request_id FROM chat_request WHERE accepted_at < ? ORDER BY accepted_at LIMIT ?) x
        WHERE r.enterprise_id = x.enterprise_id AND r.user_id = x.user_id AND r.client_request_id = x.client_request_id
        """, Timestamp.from(before), limit);
  }

  Optional<RequestRow> request(UUID enterpriseId, UUID userId, UUID clientRequestId) {
    return jdbc.query("""
        SELECT r.kb_id, r.digest, m.conversation_id, r.message_id, m.status, r.accepted_at
        FROM chat_request r LEFT JOIN message m ON m.id = r.message_id
        WHERE r.enterprise_id = ? AND r.user_id = ? AND r.client_request_id = ?
        """, (rs, i) -> new RequestRow(rs.getObject("kb_id", UUID.class), rs.getString("digest"), rs.getObject("conversation_id", UUID.class),
            rs.getObject("message_id", UUID.class), rs.getString("status"), rs.getTimestamp("accepted_at").toInstant()),
        enterpriseId, userId, clientRequestId).stream().findFirst();
  }

  static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
