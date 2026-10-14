package dev.zhiku.document;

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
      FROM content_version v JOIN document d ON d.id = v.document_id JOIN knowledge_base kb ON kb.id = d.kb_id
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
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document WHERE id = ? AND kb_id = ?)", Boolean.class, documentId, kbId));
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

  public List<DocumentView> documentsIn(UUID kbId) {
    var docs = new LinkedHashMap<UUID, DocumentView>();
    jdbc.query("SELECT id, title, created_at FROM document WHERE kb_id = ? ORDER BY created_at DESC, id", rs -> {
      var id = rs.getObject("id", UUID.class);
      docs.put(id, new DocumentView(id, rs.getString("title"), instant(rs, "created_at"), new ArrayList<>()));
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
