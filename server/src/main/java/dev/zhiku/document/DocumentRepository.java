package dev.zhiku.document;

import dev.zhiku.document.DocumentViews.DeletionView;
import dev.zhiku.document.DocumentViews.DocumentView;
import dev.zhiku.document.DocumentViews.VersionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
  /** Row of content_version joined with its document and knowledge base. */
  public record VersionRow(UUID id, UUID documentId, String documentTitle, UUID kbId, UUID enterpriseId, String versionLabel,
      Instant effectiveFrom, Instant effectiveUntil, boolean approved, boolean published, boolean withdrawn, boolean superseded,
      String indexStatus, String indexError, Instant documentCreatedAt) {

    public VersionEligibility.Version toPolicy() {
      return new VersionEligibility.Version(id, enterpriseId, kbId, approved, published, withdrawn, superseded,
          "ready".equals(indexStatus), effectiveFrom, effectiveUntil);
    }
  }

  private static final String VERSION_SELECT = """
      SELECT v.id, v.document_id, d.title, d.kb_id, kb.enterprise_id, v.version_label, v.effective_from, v.effective_until,
             v.approved_at IS NOT NULL AS approved, v.published_at IS NOT NULL AS published, v.withdrawn_at IS NOT NULL AS withdrawn,
             v.superseded, v.index_status, v.index_error, d.created_at AS document_created_at
      FROM content_version v JOIN document d ON d.id = v.document_id AND d.deleted_at IS NULL JOIN knowledge_base kb ON kb.id = d.kb_id
      """;

  private final JdbcTemplate jdbc;

  public DocumentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static VersionRow versionRow(ResultSet rs, int i) throws SQLException {
    return new VersionRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("title"),
        rs.getObject("kb_id", UUID.class), rs.getObject("enterprise_id", UUID.class), rs.getString("version_label"),
        instant(rs, "effective_from"), instant(rs, "effective_until"), rs.getBoolean("approved"), rs.getBoolean("published"),
        rs.getBoolean("withdrawn"), rs.getBoolean("superseded"), rs.getString("index_status"), rs.getString("index_error"),
        instant(rs, "document_created_at"));
  }

  public void insertDocument(UUID id, UUID kbId, String title, UUID createdBy) {
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", id, kbId, title, createdBy);
  }

  public boolean documentInKnowledgeBase(UUID documentId, UUID kbId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document WHERE id = ? AND kb_id = ? AND deleted_at IS NULL)", Boolean.class, documentId, kbId));
  }

  public boolean labelTaken(UUID documentId, String label) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM content_version WHERE document_id = ? AND version_label = ?)", Boolean.class, documentId, label));
  }

  public void insertVersion(UUID id, UUID documentId, String label, Instant from, Instant until, UUID createdBy) {
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, effective_until, created_by) VALUES (?, ?, ?, ?, ?, ?)",
        id, documentId, label, Timestamp.from(from), until == null ? null : Timestamp.from(until), createdBy);
  }

  public Optional<VersionRow> findVersion(UUID kbId, UUID documentId, UUID versionId) {
    return jdbc.query(VERSION_SELECT + " WHERE d.kb_id = ? AND d.id = ? AND v.id = ?", DocumentRepository::versionRow, kbId, documentId, versionId)
        .stream().findFirst();
  }

  public Optional<VersionRow> findVersion(UUID versionId) {
    return jdbc.query(VERSION_SELECT + " WHERE v.id = ?", DocumentRepository::versionRow, versionId).stream().findFirst();
  }

  public List<VersionRow> versionsIn(UUID kbId) {
    return jdbc.query(VERSION_SELECT + " WHERE d.kb_id = ? ORDER BY d.title, d.id, v.effective_from", DocumentRepository::versionRow, kbId);
  }

  public int approve(UUID versionId, UUID by) {
    return jdbc.update("UPDATE content_version SET approved_by = ?, approved_at = now() WHERE id = ? AND approved_at IS NULL AND withdrawn_at IS NULL", by, versionId);
  }

  public int publish(UUID versionId, UUID by) {
    return jdbc.update("UPDATE content_version SET published_by = ?, published_at = now() WHERE id = ? AND approved_at IS NOT NULL AND published_at IS NULL AND withdrawn_at IS NULL", by, versionId);
  }

  public int withdraw(UUID versionId) {
    return jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE id = ? AND withdrawn_at IS NULL", versionId);
  }

  /**
   * Share-locks the document row for the rest of the transaction; false when it is deleted. Writers of content call this
   * right before writing: a deletion then waits for them to commit (so cleanup sees their rows), and once a deletion
   * has committed every later writer is refused.
   */
  public boolean lockLiveDocument(UUID documentId) {
    return !jdbc.queryForList("SELECT 1 FROM document WHERE id = ? AND deleted_at IS NULL FOR SHARE", Integer.class, documentId).isEmpty();
  }

  /** Marks the document deleted and queues cleanup; returns 0 when it is already deleted. */
  public int markDeleted(UUID documentId, UUID by) {
    return jdbc.update("UPDATE document SET deleted_at = now(), deleted_by = ?, cleanup_status = 'pending' WHERE id = ? AND deleted_at IS NULL",
        by, documentId);
  }

  /** Re-queues a cleanup that failed permanently; returns 0 when it is pending, running or done. */
  public int retryCleanup(UUID documentId, UUID kbId) {
    return jdbc.update("UPDATE document SET cleanup_status = 'pending', cleanup_attempts = 0, cleanup_error = NULL, cleanup_lease_until = NULL "
        + "WHERE id = ? AND kb_id = ? AND cleanup_status = 'failed'", documentId, kbId);
  }

  public boolean deletedDocumentInKnowledgeBase(UUID documentId, UUID kbId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM document WHERE id = ? AND kb_id = ? AND deleted_at IS NOT NULL)", Boolean.class, documentId, kbId));
  }

  public void failExhaustedCleanups(int maxAttempts) {
    jdbc.update("UPDATE document SET cleanup_status = 'failed', cleanup_error = '多次清理失败或超时，已停止重试', cleanup_lease_until = NULL "
        + "WHERE cleanup_status = 'running' AND cleanup_lease_until < now() AND cleanup_attempts >= ?", maxAttempts);
  }

  /** One claim of a deleted document's cleanup; the token identifies this attempt. */
  public record CleanupClaim(UUID documentId, UUID token, int attempts) {}

  public Optional<CleanupClaim> claimCleanup(int leaseSeconds, int maxAttempts) {
    return jdbc.query("""
        UPDATE document d SET cleanup_status = 'running', cleanup_lease_until = now() + make_interval(secs => ?),
               cleanup_attempts = d.cleanup_attempts + 1, cleanup_token = ?
        WHERE d.id = (SELECT id FROM document
                      WHERE (cleanup_status = 'pending' OR (cleanup_status = 'running' AND cleanup_lease_until < now())) AND cleanup_attempts < ?
                      ORDER BY deleted_at FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING d.id, d.cleanup_token, d.cleanup_attempts
        """, (rs, i) -> new CleanupClaim(rs.getObject("id", UUID.class), rs.getObject("cleanup_token", UUID.class), rs.getInt("cleanup_attempts")),
        leaseSeconds, UUID.randomUUID(), maxAttempts).stream().findFirst();
  }

  /**
   * Removes jobs first: deleting a job row waits for a worker that still holds it, so its chunks are removed afterwards.
   * Does nothing and returns false unless this claim still owns the running cleanup (row locked for the transaction).
   */
  public boolean purgeContent(UUID documentId, UUID token) {
    if (jdbc.queryForList("SELECT 1 FROM document WHERE id = ? AND cleanup_token = ? AND cleanup_status = 'running' FOR UPDATE",
        Integer.class, documentId, token).isEmpty()) {
      return false;
    }
    var versions = "(SELECT id FROM content_version WHERE document_id = ?)";
    jdbc.update("DELETE FROM ingestion_job WHERE version_id IN " + versions, documentId);
    jdbc.update("DELETE FROM chunk WHERE version_id IN " + versions, documentId);
    jdbc.update("DELETE FROM content_blob WHERE version_id IN " + versions, documentId);
    jdbc.update("UPDATE document SET cleanup_status = 'done', cleanup_error = NULL, cleanup_lease_until = NULL WHERE id = ?", documentId);
    return true;
  }

  public void releaseCleanupAfterFailure(UUID documentId, UUID token, boolean exhausted, String reason) {
    jdbc.update("UPDATE document SET cleanup_status = ?, cleanup_error = ?, cleanup_lease_until = NULL "
        + "WHERE id = ? AND cleanup_token = ? AND cleanup_status = 'running'", exhausted ? "failed" : "pending", reason, documentId, token);
  }

  public List<DocumentView> documentsIn(UUID kbId) {
    var docs = new LinkedHashMap<UUID, DocumentView>();
    jdbc.query("SELECT id, title, created_at, deleted_at, cleanup_status, cleanup_error FROM document WHERE kb_id = ? ORDER BY created_at DESC, id", rs -> {
      var id = rs.getObject("id", UUID.class);
      var deletion = rs.getString("cleanup_status") == null ? null
          : new DeletionView(rs.getString("cleanup_status"), instant(rs, "deleted_at"), rs.getString("cleanup_error"));
      docs.put(id, new DocumentView(id, rs.getString("title"), instant(rs, "created_at"), new ArrayList<>(), deletion));
    }, kbId);
    for (var row : versionsIn(kbId)) {
      var doc = docs.get(row.documentId());
      if (doc != null) {
        doc.versions().add(new VersionView(row.id(), row.versionLabel(), row.effectiveFrom(), row.effectiveUntil(), row.approved(),
            row.published(), row.withdrawn(), row.superseded(), row.indexStatus(), row.indexError()));
      }
    }
    return List.copyOf(docs.values());
  }
}
