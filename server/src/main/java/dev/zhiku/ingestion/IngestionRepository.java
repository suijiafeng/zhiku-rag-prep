package dev.zhiku.ingestion;

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

  /** Re-queues a finished job; returns 0 when the job is queued or running. */
  public int requeue(UUID versionId) {
    int n = jdbc.update("UPDATE ingestion_job SET status = 'queued', attempts = 0, last_error = NULL, lease_owner = NULL, lease_until = NULL, updated_at = now() "
        + "WHERE version_id = ? AND status IN ('succeeded', 'failed')", versionId);
    if (n == 1) jdbc.update("UPDATE content_version SET index_status = 'pending', index_error = NULL WHERE id = ?", versionId);
    return n;
  }

  public void failExhausted(int maxAttempts) {
    jdbc.update("""
        WITH failed AS (
          UPDATE ingestion_job SET status = 'failed', last_error = 'Attempts exhausted', lease_owner = NULL, lease_until = NULL, updated_at = now()
          WHERE status = 'running' AND lease_until < now() AND attempts >= ?
          RETURNING version_id)
        UPDATE content_version SET index_status = 'failed', index_error = '多次处理失败或超时，已停止重试'
        WHERE id IN (SELECT version_id FROM failed)
        """, maxAttempts);
  }

  public Optional<ClaimedJob> claim(String owner, int leaseSeconds, int maxAttempts) {
    return jdbc.query("""
        UPDATE ingestion_job j SET status = 'running', lease_owner = ?, lease_until = now() + make_interval(secs => ?),
               attempts = j.attempts + 1, updated_at = now()
        WHERE j.id = (SELECT id FROM ingestion_job
                      WHERE (status = 'queued' OR (status = 'running' AND lease_until < now())) AND attempts < ?
                      ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING j.id, j.version_id, j.attempts
        """, (rs, i) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class), rs.getInt("attempts")),
        owner, leaseSeconds, maxAttempts).stream().findFirst();
  }

  public void markProcessing(UUID versionId) {
    jdbc.update("UPDATE content_version SET index_status = 'processing', index_error = NULL WHERE id = ?", versionId);
  }

  public Blob blob(UUID versionId) {
    return jdbc.queryForObject("SELECT media_type, content FROM content_blob WHERE version_id = ?",
        (rs, i) -> new Blob(rs.getString("media_type"), rs.getBytes("content")), versionId);
  }

  /** Locks the job row; true only while this worker still owns an active lease. */
  public boolean holdsLease(UUID jobId, String owner) {
    return !jdbc.queryForList("SELECT 1 FROM ingestion_job WHERE id = ? AND status = 'running' AND lease_owner = ? FOR UPDATE",
        Integer.class, jobId, owner).isEmpty();
  }

  /** Replaces all chunks of the version and returns their ids in sequence order. */
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

  public void succeed(UUID jobId, UUID versionId) {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded', lease_owner = NULL, lease_until = NULL, last_error = NULL, updated_at = now() WHERE id = ?", jobId);
    jdbc.update("UPDATE content_version SET index_status = 'ready', index_error = NULL WHERE id = ?", versionId);
  }

  /** Releases the lease after a failure: retry later, or fail permanently once attempts are exhausted. */
  public void releaseAfterFailure(UUID jobId, UUID versionId, String owner, boolean exhausted, String reason) {
    int n = jdbc.update("UPDATE ingestion_job SET status = ?, lease_owner = NULL, lease_until = NULL, last_error = ?, updated_at = now() "
        + "WHERE id = ? AND lease_owner = ? AND status = 'running'", exhausted ? "failed" : "queued", reason, jobId, owner);
    if (n == 1) {
      jdbc.update("UPDATE content_version SET index_status = ?, index_error = ? WHERE id = ?", exhausted ? "failed" : "pending", exhausted ? reason : null, versionId);
    }
  }
}
