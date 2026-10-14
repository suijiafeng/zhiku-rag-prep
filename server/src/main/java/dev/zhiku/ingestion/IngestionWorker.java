package dev.zhiku.ingestion;

import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.ingestion.IngestionRepository.ClaimedJob;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 处理持久化的导入任务。认领使用 FOR UPDATE SKIP LOCKED 加 lease；结果仅在仍持有 lease 时
 * 于同一事务中写入，因此崩溃或缓慢的 worker 绝不会产生重复输出。
 */
@Component
public class IngestionWorker {
  private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
  static final long RETRY_BASE_SECONDS = 30;
  private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
    var t = new Thread(r, "ingestion-lease");
    t.setDaemon(true);
    return t;
  });

  private final IngestionRepository repository;
  private final IngestionSettings settings;
  private final TransactionTemplate tx;
  private final TextChunker chunker;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final String owner = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID();
  private final java.util.concurrent.atomic.AtomicBoolean warnedNoEmbedding = new java.util.concurrent.atomic.AtomicBoolean();

  public IngestionWorker(IngestionRepository repository, IngestionSettings settings, TransactionTemplate tx,
      ObjectProvider<EmbeddingProvider> embeddings) {
    this.repository = repository;
    this.embeddings = embeddings;
    this.settings = settings;
    this.tx = tx;
    this.chunker = new TextChunker(settings.chunkMaxChars(), settings.chunkOverlap(), settings.maxChunks());
  }

  /** 最多处理一个任务；返回是否认领到任务。 */
  public boolean processNext() {
    if (!canProcess()) return false;
    repository.failExhausted(settings.maxAttempts());
    var job = claim();
    job.ifPresent(this::process);
    return job.isPresent();
  }

  /**
   * 任务都需要向量化，没有 embedding 提供方的实例不具备执行能力。多个实例共用一个库时，
   * 这样的实例不能认领任务，否则会把别的实例能完成的任务永久标为失败。
   */
  public boolean canProcess() {
    if (embeddings.getIfAvailable() != null) return true;
    if (warnedNoEmbedding.compareAndSet(false, true)) log.warn("No embedding provider configured; this instance will not claim ingestion jobs");
    return false;
  }

  public Optional<ClaimedJob> claim() {
    if (!canProcess()) return Optional.empty();
    var job = repository.claim(owner, settings.leaseSeconds(), settings.maxAttempts());
    job.ifPresent(j -> repository.markProcessing(j.versionId()));
    return job;
  }

  public void process(ClaimedJob job) {
    // 处理期间按租约的三分之一周期续租，健康的长任务不会被其他 worker 接管。
    long period = Math.max(100, settings.leaseSeconds() * 1000L / 3);
    // 续租失败说明任务已被接管：停止剩余的向量化批次，不再浪费计算；提交时仍以所有权检查为准。
    var lost = new java.util.concurrent.atomic.AtomicBoolean();
    var heartbeat = HEARTBEAT.scheduleAtFixedRate(() -> {
      try {
        if (!repository.renewLease(job.jobId(), owner, settings.leaseSeconds())) lost.set(true);
      } catch (RuntimeException e) {
        lost.set(true);
      }
    }, period, period, TimeUnit.MILLISECONDS);
    try {
      var blob = repository.blob(job.versionId());
      var chunks = chunker.chunk(new String(blob.content(), StandardCharsets.UTF_8).replaceFirst("^﻿", ""), "text/markdown".equals(blob.mediaType()));
      var provider = embeddings.getIfAvailable();
      // 认领时已确认有提供方；运行中被移除按普通失败处理，任务回到队列由其他实例接手。
      if (provider == null) throw new IllegalStateException("embedding provider unavailable");
      // 向量在写事务之前计算，因此模型调用期间不持有行锁。
      var vectors = chunks.isEmpty() ? List.<float[]>of() : provider.embed(chunks.stream().map(TextChunker.Chunk::text).toList(), lost::get);
      int dimension = chunks.isEmpty() ? 0 : EmbeddingVectors.validate(vectors, chunks.size());
      boolean written = Boolean.TRUE.equals(tx.execute(status -> {
        if (!repository.holdsLease(job.jobId(), owner)) return false;
        var ids = repository.replaceChunks(job.versionId(), chunks);
        repository.insertEmbeddings(ids, vectors, provider.model(), dimension);
        repository.succeed(job.jobId(), job.versionId());
        return true;
      }));
      if (!written) log.warn("Ingestion job {} lost its lease; result discarded", job.jobId());
    } catch (RuntimeException e) {
      log.warn("Ingestion job {} attempt {} failed: {}", job.jobId(), job.attempts(), e.getClass().getSimpleName());
      repository.releaseAfterFailure(job.jobId(), owner, job.attempts() >= settings.maxAttempts(), reason(e), RETRY_BASE_SECONDS * job.attempts());
    } finally {
      heartbeat.cancel(false);
    }
  }

  private static String reason(RuntimeException e) {
    if (e instanceof IllegalArgumentException) return "文档超出切块上限或格式无法处理";
    return "处理或 embedding 服务失败，稍后重试";
  }
}
