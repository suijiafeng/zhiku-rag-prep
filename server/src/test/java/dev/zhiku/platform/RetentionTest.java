package dev.zhiku.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 轨迹与请求去重记录按保留期分批删除；未到期的保留，问答记录不受影响。 */
class RetentionTest extends IntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired RetentionWorker worker;
  @Autowired RetentionSettings settings;

  final UUID enterprise = UUID.randomUUID();
  final UUID user = UUID.randomUUID();

  UUID trace(UUID kb, Instant createdAt) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO retrieval_trace (id, enterprise_id, kb_id, user_id, origin, at, k, created_at) VALUES (?, ?, ?, ?, 'debug', ?, 8, ?)",
        id, enterprise, kb, user, Timestamp.from(createdAt), Timestamp.from(createdAt));
    jdbc.update("INSERT INTO retrieval_trace_stage (trace_id, ordinal, key, name, status) VALUES (?, 1, 'vector', '向量召回', 'success')", id);
    jdbc.update("INSERT INTO retrieval_trace_candidate (trace_id, rank, chunk_id, version_id, score) VALUES (?, 1, ?, ?, 0.5)", id, UUID.randomUUID(), UUID.randomUUID());
    return id;
  }

  void request(UUID kb, Instant acceptedAt) {
    jdbc.update("INSERT INTO chat_request (enterprise_id, user_id, client_request_id, kb_id, digest, accepted_at) VALUES (?, ?, ?, ?, ?, ?)",
        enterprise, user, UUID.randomUUID(), kb, "a".repeat(64), Timestamp.from(acceptedAt));
  }

  int count(String table, String column, Object id) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
  }

  @Test
  void expiredTracesAndRequestsAreRemovedInBatchesAndRecentOnesStay() {
    var kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "保留-" + kb);
    var now = Instant.now();
    var old = now.minus(Duration.ofDays(settings.traceDays() + 1));
    for (int i = 0; i < settings.batchSize() + 3; i++) trace(kb, old);
    var recent = trace(kb, now.minus(Duration.ofDays(settings.traceDays() - 1)));
    request(kb, now.minus(Duration.ofDays(settings.requestDays() + 1)));
    request(kb, now.minus(Duration.ofHours(1)));

    var removed = worker.purge(now);

    assertThat(removed[0]).isEqualTo(settings.batchSize() + 3);
    assertThat(removed[1]).isEqualTo(1);
    assertThat(count("retrieval_trace", "kb_id", kb)).isEqualTo(1);
    assertThat(count("retrieval_trace_stage", "trace_id", recent)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_trace_stage s JOIN retrieval_trace t ON t.id = s.trace_id WHERE t.kb_id = ?", Integer.class, kb)).isEqualTo(1);
    assertThat(count("chat_request", "kb_id", kb)).isEqualTo(1);
    assertThat(worker.purge(now)).containsExactly(0, 0);
  }
}
