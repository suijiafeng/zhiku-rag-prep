package dev.zhiku.retrieval;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.retrieval.Retrieval.Candidate;
import dev.zhiku.retrieval.Retrieval.Stage;
import dev.zhiku.retrieval.Retrieval.TraceCandidate;
import dev.zhiku.retrieval.Retrieval.TraceView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TraceRepository {
  private final JdbcTemplate jdbc;

  TraceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  void insert(UUID id, AccessScope scope, UUID kbId, String origin, Instant at, int k, String model, int configVersion) {
    jdbc.update("INSERT INTO retrieval_trace (id, enterprise_id, kb_id, user_id, origin, at, k, embedding_model, config_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id, scope.enterpriseId(), kbId, scope.userId(), origin, ts(at), k, model, configVersion);
  }

  void setDimension(UUID id, int dimension) {
    jdbc.update("UPDATE retrieval_trace SET dimension = ? WHERE id = ?", dimension, id);
  }

  void addStages(UUID id, List<Stage> stages) {
    var next = jdbc.queryForObject("SELECT coalesce(max(ordinal), 0) FROM retrieval_trace_stage WHERE trace_id = ?", Integer.class, id);
    for (int i = 0; i < stages.size(); i++) {
      var s = stages.get(i);
      jdbc.update("INSERT INTO retrieval_trace_stage (trace_id, ordinal, key, name, status, started_at, ended_at, elapsed_ms, summary) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, next + i + 1, s.key(), s.name(), s.status(), ts(s.startedAt()), ts(s.endedAt()),
          s.elapsedMs(), s.summary());
    }
  }

  void addCandidates(UUID id, List<Candidate> candidates) {
    for (var c : candidates) {
      jdbc.update("INSERT INTO retrieval_trace_candidate (trace_id, rank, chunk_id, version_id, score) VALUES (?, ?, ?, ?, ?)",
          id, c.rank(), c.chunkId(), c.versionId(), c.score());
    }
  }

  void linkMessage(UUID id, UUID messageId) {
    jdbc.update("UPDATE retrieval_trace SET message_id = ? WHERE id = ?", messageId, id);
  }

  /** 分批删除早于 before 的轨迹；阶段与候选随外键级联删除，问答记录只是失去轨迹链接。 */
  int deleteCreatedBefore(Instant before, int limit) {
    return jdbc.update("DELETE FROM retrieval_trace WHERE id IN (SELECT id FROM retrieval_trace WHERE created_at < ? ORDER BY created_at LIMIT ?)",
        ts(before), limit);
  }

  boolean exists(UUID id) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM retrieval_trace WHERE id = ?)", Boolean.class, id));
  }

  Optional<TraceView> find(UUID kbId, UUID id) {
    var stages = jdbc.query("SELECT key, name, status, started_at, ended_at, elapsed_ms, summary FROM retrieval_trace_stage WHERE trace_id = ? ORDER BY ordinal",
        (rs, i) -> new Stage(rs.getString("key"), rs.getString("name"), rs.getString("status"), instant(rs, "started_at"), instant(rs, "ended_at"),
            (Long) rs.getObject("elapsed_ms"), rs.getString("summary")), id);
    // 已删除文档不再显示标题，只留编号与得分。
    var candidates = jdbc.query("""
        SELECT c.rank, c.chunk_id, c.version_id, c.score, d.title
        FROM retrieval_trace_candidate c
        LEFT JOIN content_version v ON v.id = c.version_id
        LEFT JOIN document d ON d.id = v.document_id AND d.deleted_at IS NULL
        WHERE c.trace_id = ? ORDER BY c.rank
        """, (rs, i) -> new TraceCandidate(rs.getInt("rank"), rs.getObject("chunk_id", UUID.class), rs.getObject("version_id", UUID.class),
            rs.getString("title"), rs.getDouble("score")), id);
    return jdbc.query("SELECT id, origin, at, k, embedding_model, dimension, message_id, created_at, config_version FROM retrieval_trace WHERE id = ? AND kb_id = ?",
        (rs, i) -> new TraceView(rs.getObject("id", UUID.class), rs.getString("origin"), instant(rs, "at"), rs.getInt("k"),
            rs.getString("embedding_model"), (Integer) rs.getObject("dimension"), rs.getObject("message_id", UUID.class),
            instant(rs, "created_at"), stages, candidates, rs.getInt("config_version")), id, kbId).stream().findFirst();
  }
}
