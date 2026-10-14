package dev.zhiku.config;

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
class ConfigRepository {
  record Row(UUID id, UUID kbId, String status, Integer version, int revision, int retrievalK, int contextChunks, String handoffChannel,
      String promptAddendum, String note, UUID createdBy, Instant createdAt, Instant validatedAt, Integer validatedRevision,
      UUID validationRunId, Integer validationCases, UUID publishedBy, Instant publishedAt, Integer rolledBackFrom) {}

  private static final String SELECT = """
      SELECT id, kb_id, status, version, revision, retrieval_k, context_chunks, handoff_channel, prompt_addendum, note, created_by, created_at,
             validated_at, validated_revision, validation_run_id, validation_cases, published_by, published_at, rolled_back_from
      FROM kb_config
      """;

  private final JdbcTemplate jdbc;

  ConfigRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    return new Row(rs.getObject("id", UUID.class), rs.getObject("kb_id", UUID.class), rs.getString("status"), (Integer) rs.getObject("version"),
        rs.getInt("revision"), rs.getInt("retrieval_k"), rs.getInt("context_chunks"), rs.getString("handoff_channel"), rs.getString("prompt_addendum"),
        rs.getString("note"), rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "validated_at"),
        (Integer) rs.getObject("validated_revision"), rs.getObject("validation_run_id", UUID.class), (Integer) rs.getObject("validation_cases"),
        rs.getObject("published_by", UUID.class), instant(rs, "published_at"), (Integer) rs.getObject("rolled_back_from"));
  }

  Optional<Row> candidate(UUID kbId, boolean lock) {
    return jdbc.query(SELECT + " WHERE kb_id = ? AND status = 'candidate'" + (lock ? " FOR UPDATE" : ""), ConfigRepository::row, kbId).stream().findFirst();
  }

  Optional<Row> published(UUID kbId) {
    return jdbc.query(SELECT + " WHERE kb_id = ? AND status = 'published'", ConfigRepository::row, kbId).stream().findFirst();
  }

  Optional<Row> version(UUID kbId, int version) {
    return jdbc.query(SELECT + " WHERE kb_id = ? AND version = ?", ConfigRepository::row, kbId, version).stream().findFirst();
  }

  List<Row> history(UUID kbId) {
    return jdbc.query(SELECT + " WHERE kb_id = ? AND status <> 'candidate' ORDER BY version DESC", ConfigRepository::row, kbId);
  }

  UUID insertCandidate(UUID kbId, ConfigService.Input in, UUID by) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO kb_config (id, kb_id, status, retrieval_k, context_chunks, handoff_channel, prompt_addendum, note, created_by) "
        + "VALUES (?, ?, 'candidate', ?, ?, ?, ?, ?, ?)", id, kbId, in.retrievalK(), in.contextChunks(), in.handoffChannel(), in.promptAddendum(), in.note(), by);
    return id;
  }

  /** 修改候选会使此前的验证失效；返回 0 表示修订号已变。 */
  int updateCandidate(UUID id, int expectedRevision, ConfigService.Input in) {
    return jdbc.update("""
        UPDATE kb_config SET retrieval_k = ?, context_chunks = ?, handoff_channel = ?, prompt_addendum = ?, note = ?, revision = revision + 1,
               validated_at = NULL, validated_revision = NULL, validation_run_id = NULL, validation_cases = NULL, updated_at = now()
        WHERE id = ? AND revision = ? AND status = 'candidate'
        """, in.retrievalK(), in.contextChunks(), in.handoffChannel(), in.promptAddendum(), in.note(), id, expectedRevision);
  }

  void markValidated(UUID id, int revision, UUID runId, int cases) {
    jdbc.update("UPDATE kb_config SET validated_at = now(), validated_revision = ?, validation_run_id = ?, validation_cases = ?, updated_at = now() WHERE id = ?",
        revision, runId, cases, id);
  }

  /** 发布与回滚共用同一把事务级知识库锁，版本号在锁内计算，并发时后到者等待而不是撞号。 */
  private void lockVersionSequence(UUID kbId) {
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('kb_config'), hashtext(?))", Object.class, kbId.toString());
  }

  /** 把候选发布为下一个版本，并把当前已发布版本标为被替代；在同一事务中锁定知识库的配置行。 */
  int publish(UUID kbId, UUID candidateId, UUID by) {
    lockVersionSequence(kbId);
    jdbc.update("UPDATE kb_config SET status = 'superseded', updated_at = now() WHERE kb_id = ? AND status = 'published'", kbId);
    var next = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM kb_config WHERE kb_id = ?", Integer.class, kbId);
    jdbc.update("UPDATE kb_config SET status = 'published', version = ?, published_by = ?, published_at = now(), updated_at = now() WHERE id = ?",
        next, by, candidateId);
    return next;
  }

  int republish(UUID kbId, Row from, UUID by) {
    lockVersionSequence(kbId);
    jdbc.update("UPDATE kb_config SET status = 'superseded', updated_at = now() WHERE kb_id = ? AND status = 'published'", kbId);
    var next = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM kb_config WHERE kb_id = ?", Integer.class, kbId);
    jdbc.update("""
        INSERT INTO kb_config (id, kb_id, status, version, retrieval_k, context_chunks, handoff_channel, prompt_addendum, note, created_by,
                               validated_at, validated_revision, validation_run_id, validation_cases, published_by, published_at, rolled_back_from)
        VALUES (?, ?, 'published', ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, now(), ?)
        """, UUID.randomUUID(), kbId, next, from.retrievalK(), from.contextChunks(), from.handoffChannel(), from.promptAddendum(),
        "回滚自版本 " + from.version(), by, from.validatedAt() == null ? null : Timestamp.from(from.validatedAt()), from.validationRunId(),
        from.validationCases(), by, from.version());
    return next;
  }
}
