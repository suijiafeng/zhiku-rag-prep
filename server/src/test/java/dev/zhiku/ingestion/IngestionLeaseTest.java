package dev.zhiku.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestEmbeddingProvider;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 租约只有 1 秒：健康的长任务要靠续租，才不会被另一个 worker 接管。 */
@TestPropertySource(properties = "zhiku.ingestion.lease-seconds=1")
class IngestionLeaseTest extends IntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired IngestionRepository repository;
  @Autowired IngestionSettings settings;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectProvider<EmbeddingProvider> providers;
  @Autowired TestEmbeddingProvider embeddings;

  @AfterEach
  void restore() {
    embeddings.beforeEmbed = () -> {};
  }

  @Test
  void aLostLeaseStopsTheEmbeddingAndWritesNothing() throws Exception {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    var kb = UUID.randomUUID();
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    var user = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, UUID.randomUUID(), "失租-" + kb);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '手册', ?)", doc, kb, user);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v1', now(), ?)", version, doc, user);
    jdbc.update("INSERT INTO content_blob (version_id, media_type, file_name, byte_size, sha256, content, uploaded_by) VALUES (?, 'text/plain', 'a.txt', 6, repeat('a', 64), ?, ?)",
        version, "年假五天".getBytes(StandardCharsets.UTF_8), user);
    repository.enqueue(version);
    var worker = new IngestionWorker(repository, settings, tx, providers);
    var job = worker.claim().orElseThrow();
    embeddings.beforeEmbed = () -> {
      jdbc.update("UPDATE ingestion_job SET lease_owner = 'thief' WHERE id = ?", job.jobId());
      try {
        Thread.sleep(1200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    worker.process(job);
    assertThat(jdbc.queryForObject("SELECT lease_owner || ':' || status FROM ingestion_job WHERE id = ?", String.class, job.jobId())).isEqualTo("thief:running");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk WHERE version_id = ?", Integer.class, version)).isZero();
    assertThat(embeddings.stoppedCalls.get()).isEqualTo(1);
  }

  @Test
  void aHealthyLongJobKeepsItsLeaseAndIsNotTakenOver() throws Exception {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    var kb = UUID.randomUUID();
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    var user = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, UUID.randomUUID(), "续租-" + kb);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '手册', ?)", doc, kb, user);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by) VALUES (?, ?, 'v1', now(), ?)", version, doc, user);
    jdbc.update("INSERT INTO content_blob (version_id, media_type, file_name, byte_size, sha256, content, uploaded_by) VALUES (?, 'text/plain', 'a.txt', 6, repeat('a', 64), ?, ?)",
        version, "年假五天".getBytes(StandardCharsets.UTF_8), user);
    repository.enqueue(version);

    var first = new IngestionWorker(repository, settings, tx, providers);
    var second = new IngestionWorker(repository, settings, tx, providers);
    embeddings.beforeEmbed = () -> {
      try {
        Thread.sleep(2500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    var job = first.claim().orElseThrow();
    var running = CompletableFuture.runAsync(() -> first.process(job));
    Thread.sleep(1500);
    assertThat(second.claim()).isEmpty();
    running.get(10, TimeUnit.SECONDS);
    assertThat(jdbc.queryForObject("SELECT status FROM ingestion_job WHERE version_id = ?", String.class, version)).isEqualTo("succeeded");
    assertThat(jdbc.queryForObject("SELECT attempts FROM ingestion_job WHERE version_id = ?", Integer.class, version)).isEqualTo(1);
  }
}
