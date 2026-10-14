package dev.zhiku.ingestion;

import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.ingestion.TextChunker.Chunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRepository {
  public record ClaimedJob(UUID jobId, UUID versionId, int attempts) {}

  public record Blob(String mediaType, byte[] content) {}

  private final JdbcTemplate jdbc;

  public IngestionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean hasBlob(UUID versionId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM content_blob WHERE version_id = ?)", Boolean.class, versionId));
  }

  public void insertBlob(UUID versionId, UploadValidator.Accepted file, byte[] bytes, UUID uploadedBy) {
    jdbc.update("INSERT INTO content_blob (version_id, media_type, file_name, byte_size, sha256, content, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
        versionId, file.mediaType(), file.fileName(), bytes.length, file.sha256(), bytes, uploadedBy);
  }

  public UUID enqueue(UUID versionId) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO ingestion_job (id, version_id, status) VALUES (?, ?, 'queued')", id, versionId);
    jdbc.update("UPDATE content_version SET index_status = 'pending', index_error = NULL WHERE id = ?", versionId);
    return id;
  }

  static final String REBUILD_FAILED = "重建失败，仍使用原索引";

  /** 已就绪的版本在重建成功前保持可检索，新切块在同一事务中整体替换旧切块。 */
  public int requeue(UUID versionId) {
    int n = jdbc.update("UPDATE ingestion_job SET status = 'queued', attempts = 0, last_error = NULL, lease_owner = NULL, lease_until = NULL, "
        + "next_attempt_at = NULL, updated_at = now() WHERE version_id = ? AND status IN ('succeeded', 'failed')", versionId);
    if (n == 1) {
      jdbc.update("UPDATE content_version SET index_status = CASE WHEN index_status = 'ready' THEN 'ready' ELSE 'pending' END, index_error = NULL WHERE id = ?",
          versionId);
    }
    return n;
  }

  public void failExhausted(int maxAttempts) {
    jdbc.update("""
        WITH failed AS (
          UPDATE ingestion_job SET status = 'failed', last_error = 'Attempts exhausted', lease_owner = NULL, lease_until = NULL, updated_at = now()
          WHERE status = 'running' AND lease_until < now() AND attempts >= ?
          RETURNING version_id)
        UPDATE content_version SET index_status = CASE WHEN index_status = 'ready' THEN 'ready' ELSE 'failed' END,
               index_error = CASE WHEN index_status = 'ready' THEN ? ELSE '多次处理失败或超时，已停止重试' END
        WHERE id IN (SELECT version_id FROM failed)
        """, maxAttempts, REBUILD_FAILED);
  }

  public Optional<ClaimedJob> claim(String owner, int leaseSeconds, int maxAttempts) {
    return jdbc.query("""
        UPDATE ingestion_job j SET status = 'running', lease_owner = ?, lease_until = now() + make_interval(secs => ?),
               attempts = j.attempts + 1, updated_at = now()
        WHERE j.id = (SELECT id FROM ingestion_job
                      WHERE (status = 'queued' OR (status = 'running' AND lease_until < now())) AND attempts < ?
                        AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                        AND NOT EXISTS (SELECT 1 FROM content_version v JOIN document d ON d.id = v.document_id
                                        WHERE v.id = ingestion_job.version_id AND d.deleted_at IS NOT NULL)
                      ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING j.id, j.version_id, j.attempts
        """, (rs, i) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class), rs.getInt("attempts")),
        owner, leaseSeconds, maxAttempts).stream().findFirst();
  }

  public void markProcessing(UUID versionId) {
    jdbc.update("UPDATE content_version SET index_status = 'processing', index_error = NULL WHERE id = ? AND index_status <> 'ready'", versionId);
  }

  /** 本实例无法向量化时在排队状态上说明原因；任务仍留在队列，等待具备能力的实例。 */
  public void noteWaitingForEmbedding(UUID versionId) {
    jdbc.update("UPDATE content_version SET index_error = ? WHERE id = ? AND index_status = 'pending'",
        "接收上传的实例未配置 embedding 模型，任务排队等待可向量化的实例处理", versionId);
  }

  public Blob blob(UUID versionId) {
    return jdbc.queryForObject("SELECT media_type, content FROM content_blob WHERE version_id = ?",
        (rs, i) -> new Blob(rs.getString("media_type"), rs.getBytes("content")), versionId);
  }

  /** 仍持有租约时续期；返回 false 表示任务已被接管。 */
  public boolean renewLease(UUID jobId, String owner, int leaseSeconds) {
    return jdbc.update("UPDATE ingestion_job SET lease_until = now() + make_interval(secs => ?), updated_at = now() "
        + "WHERE id = ? AND lease_owner = ? AND status = 'running'", leaseSeconds, jobId, owner) == 1;
  }

  /** 锁定任务行；仅当本 worker 仍持有有效 lease 时返回 true。 */
  public boolean holdsLease(UUID jobId, String owner) {
    return !jdbc.queryForList("SELECT 1 FROM ingestion_job WHERE id = ? AND status = 'running' AND lease_owner = ? FOR UPDATE",
        Integer.class, jobId, owner).isEmpty();
  }

  /** 替换该版本的全部 chunk，并按序号顺序返回其 id。 */
  public List<UUID> replaceChunks(UUID versionId, List<Chunk> chunks) {
    jdbc.update("DELETE FROM chunk WHERE version_id = ?", versionId);
    var ids = chunks.stream().map(c -> UUID.randomUUID()).toList();
    jdbc.batchUpdate("INSERT INTO chunk (id, version_id, seq, start_offset, end_offset, heading, text) VALUES (?, ?, ?, ?, ?, ?, ?)",
        chunks, 500, (ps, c) -> {
          ps.setObject(1, ids.get(c.seq()));
          ps.setObject(2, versionId);
          ps.setInt(3, c.seq());
          ps.setInt(4, c.start());
          ps.setInt(5, c.end());
          ps.setString(6, c.heading());
          ps.setString(7, c.text());
        });
    return ids;
  }

  public void insertEmbeddings(List<UUID> chunkIds, List<float[]> vectors, String model, int dimension) {
    var rows = new java.util.ArrayList<Integer>();
    for (int i = 0; i < chunkIds.size(); i++) rows.add(i);
    jdbc.batchUpdate("INSERT INTO chunk_embedding (chunk_id, model, dimension, embedding) VALUES (?, ?, ?, CAST(? AS vector))",
        rows, 200, (ps, i) -> {
          ps.setObject(1, chunkIds.get(i));
          ps.setString(2, model);
          ps.setInt(3, dimension);
          ps.setString(4, EmbeddingVectors.literal(vectors.get(i)));
        });
  }

  public void succeed(UUID jobId, UUID versionId) {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded', lease_owner = NULL, lease_until = NULL, last_error = NULL, updated_at = now() WHERE id = ?", jobId);
    jdbc.update("UPDATE content_version SET index_status = 'ready', index_error = NULL WHERE id = ?", versionId);
  }

  /** 单条语句且要求仍持有租约：过期的执行无法覆盖更新的结果。 */
  public void releaseAfterFailure(UUID jobId, String owner, boolean exhausted, String reason, long retryAfterSeconds) {
    jdbc.update("""
        WITH released AS (
          UPDATE ingestion_job SET status = ?, lease_owner = NULL, lease_until = NULL, last_error = ?, updated_at = now(),
                 next_attempt_at = CASE WHEN ? THEN NULL ELSE now() + make_interval(secs => ?) END
          WHERE id = ? AND lease_owner = ? AND status = 'running'
          RETURNING version_id)
        UPDATE content_version v SET
          index_status = CASE WHEN v.index_status = 'ready' THEN 'ready' WHEN ? THEN 'failed' ELSE 'pending' END,
          index_error = CASE WHEN v.index_status = 'ready' THEN (CASE WHEN ? THEN ? ELSE v.index_error END) WHEN ? THEN ? ELSE NULL END
        WHERE v.id IN (SELECT version_id FROM released)
        """, exhausted ? "failed" : "queued", reason, exhausted, (double) retryAfterSeconds, jobId, owner,
        exhausted, exhausted, REBUILD_FAILED, exhausted, reason);
  }
}
