package dev.zhiku.retrieval;

import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.retrieval.Retrieval.Candidate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RetrievalRepository {
  private final JdbcTemplate jdbc;

  RetrievalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 精确余弦检索，限定在已授权的有效版本和单一 embedding 空间内。 */
  List<Candidate> nearest(List<UUID> versionIds, String model, float[] query, int k) {
    if (versionIds.isEmpty()) return List.of();
    var literal = EmbeddingVectors.literal(query);
    var rank = new int[] {0};
    return jdbc.query("""
        SELECT c.id, d.id AS document_id, d.title, v.id AS version_id, v.version_label, c.seq, c.heading, c.start_offset, c.end_offset, c.text,
               1 - (e.embedding <=> CAST(? AS vector)) AS score
        FROM chunk_embedding e
        JOIN chunk c ON c.id = e.chunk_id
        JOIN content_version v ON v.id = c.version_id
        JOIN document d ON d.id = v.document_id
        WHERE c.version_id = ANY(?) AND e.model = ? AND e.dimension = ? AND d.deleted_at IS NULL AND v.withdrawn_at IS NULL
        ORDER BY e.embedding <=> CAST(? AS vector), c.id
        LIMIT ?
        """, (rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("title"),
            rs.getObject("version_id", UUID.class), rs.getString("version_label"), rs.getInt("seq"), rs.getString("heading"),
            rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("text"), rs.getDouble("score"), ++rank[0]),
        literal, versionIds.toArray(UUID[]::new), model, query.length, literal, k);
  }

  boolean allChunksExist(List<UUID> chunkIds) {
    var found = jdbc.queryForObject("SELECT count(*) FROM chunk WHERE id = ANY(?)", Integer.class, (Object) chunkIds.toArray(UUID[]::new));
    return found != null && found == chunkIds.stream().distinct().count();
  }
}
