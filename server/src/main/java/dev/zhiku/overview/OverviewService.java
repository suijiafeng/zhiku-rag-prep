package dev.zhiku.overview;

import static dev.zhiku.auth.KnowledgeBaseAction.ASK_AND_CITE;
import static dev.zhiku.auth.KnowledgeBaseAction.READ_OTHERS_CONVERSATIONS;

import dev.zhiku.document.BusinessSettings;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model for the knowledge-base overview. Aggregates only; other people's question text stays hidden. */
@Service
class OverviewService {
  record Day(LocalDate day, int count) {}

  record Feedback(int up, int down) {}

  record EmbeddingSpace(String model, int dimension, int vectors) {}

  record RecentDocument(UUID documentId, String title, String versionLabel, String indexStatus, int chunks, Instant createdAt) {}

  record RecentQuestion(UUID messageId, boolean own, String question, int citationCount, Instant createdAt) {}

  record Overview(int documents, int versions, int readyVersions, int chunks, int vectors, int questionsToday, int questionsYesterday,
      Feedback feedback, List<Day> daily, List<EmbeddingSpace> embeddingSpaces, Instant lastIndexedAt,
      List<RecentDocument> recentDocuments, List<RecentQuestion> recentQuestions) {}

  private final KnowledgeBaseAccess access;
  private final JdbcTemplate jdbc;
  private final BusinessSettings business;

  OverviewService(KnowledgeBaseAccess access, JdbcTemplate jdbc, BusinessSettings business) {
    this.access = access;
    this.jdbc = jdbc;
    this.business = business;
  }

  private int count(String sql, Object... args) {
    var n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Transactional(readOnly = true)
  Overview overview(UUID kbId) {
    var scope = access.require(kbId, ASK_AND_CITE);
    boolean readOthers = scope.permits(scope.enterpriseId(), kbId, READ_OTHERS_CONVERSATIONS);
    var today = LocalDate.now(business.timeZone());
    var start = Timestamp.from(business.startOf(today.minusDays(6)));

    var perDay = new HashMap<LocalDate, Integer>();
    jdbc.query("""
        SELECT (m.created_at AT TIME ZONE ?)::date AS d, count(*) AS n
        FROM message m JOIN conversation c ON c.id = m.conversation_id
        WHERE c.kb_id = ? AND m.created_at >= ? GROUP BY 1
        """, rs -> {
          perDay.put(rs.getObject("d", LocalDate.class), rs.getInt("n"));
        }, business.timeZone().getId(), kbId, start);
    var daily = new ArrayList<Day>();
    for (int i = 6; i >= 0; i--) {
      var d = today.minusDays(i);
      daily.add(new Day(d, perDay.getOrDefault(d, 0)));
    }

    var spaces = jdbc.query("""
        SELECT e.model, e.dimension, count(*) AS n FROM chunk_embedding e JOIN chunk c ON c.id = e.chunk_id
        JOIN content_version v ON v.id = c.version_id JOIN document d ON d.id = v.document_id
        WHERE d.kb_id = ? GROUP BY e.model, e.dimension ORDER BY n DESC
        """, (rs, i) -> new EmbeddingSpace(rs.getString("model"), rs.getInt("dimension"), rs.getInt("n")), kbId);
    var lastIndexed = jdbc.queryForObject("""
        SELECT max(j.updated_at) FROM ingestion_job j JOIN content_version v ON v.id = j.version_id JOIN document d ON d.id = v.document_id
        WHERE d.kb_id = ? AND j.status = 'succeeded'
        """, Timestamp.class, kbId);

    var recentDocuments = jdbc.query("""
        SELECT DISTINCT ON (d.id) d.id, d.title, v.version_label, v.index_status, v.created_at,
               (SELECT count(*) FROM chunk c WHERE c.version_id = v.id) AS chunks
        FROM document d JOIN content_version v ON v.document_id = d.id WHERE d.kb_id = ?
        ORDER BY d.id, v.created_at DESC
        """, (rs, i) -> new RecentDocument(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("version_label"),
            rs.getString("index_status"), rs.getInt("chunks"), rs.getTimestamp("created_at").toInstant()), kbId)
        .stream().sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).limit(5).toList();

    var recentQuestions = jdbc.query("""
        SELECT m.id, c.user_id, m.question, m.created_at, (SELECT count(*) FROM message_source s WHERE s.message_id = m.id) AS citations
        FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ? ORDER BY m.created_at DESC, m.id LIMIT 5
        """, (rs, i) -> {
          boolean own = scope.userId().equals(rs.getObject("user_id", UUID.class));
          return new RecentQuestion(rs.getObject("id", UUID.class), own, own || readOthers ? rs.getString("question") : null,
              rs.getInt("citations"), rs.getTimestamp("created_at").toInstant());
        }, kbId);

    return new Overview(
        count("SELECT count(*) FROM document WHERE kb_id = ?", kbId),
        count("SELECT count(*) FROM content_version v JOIN document d ON d.id = v.document_id WHERE d.kb_id = ?", kbId),
        count("SELECT count(*) FROM content_version v JOIN document d ON d.id = v.document_id WHERE d.kb_id = ? AND v.index_status = 'ready'", kbId),
        count("SELECT count(*) FROM chunk c JOIN content_version v ON v.id = c.version_id JOIN document d ON d.id = v.document_id WHERE d.kb_id = ?", kbId),
        spaces.stream().mapToInt(EmbeddingSpace::vectors).sum(),
        daily.get(6).count(),
        daily.get(5).count(),
        new Feedback(
            count("SELECT count(*) FROM feedback f JOIN message m ON m.id = f.message_id JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ? AND f.rating = 'up' AND m.created_at >= ?", kbId, start),
            count("SELECT count(*) FROM feedback f JOIN message m ON m.id = f.message_id JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ? AND f.rating = 'down' AND m.created_at >= ?", kbId, start)),
        daily, spaces, lastIndexed == null ? null : lastIndexed.toInstant(), recentDocuments, recentQuestions);
  }
}
