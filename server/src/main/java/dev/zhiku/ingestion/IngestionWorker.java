package dev.zhiku.ingestion;

import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.ingestion.IngestionRepository.ClaimedJob;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
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

  private final IngestionRepository repository;
  private final IngestionSettings settings;
  private final TransactionTemplate tx;
  private final TextChunker chunker;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final String owner = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID();

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
    repository.failExhausted(settings.maxAttempts());
    var job = claim();
    job.ifPresent(this::process);
    return job.isPresent();
  }

  public Optional<ClaimedJob> claim() {
    var job = repository.claim(owner, settings.leaseSeconds(), settings.maxAttempts());
    job.ifPresent(j -> repository.markProcessing(j.versionId()));
    return job;
  }

  public void process(ClaimedJob job) {
    try {
      var blob = repository.blob(job.versionId());
      var chunks = chunker.chunk(new String(blob.content(), StandardCharsets.UTF_8).replaceFirst("^﻿", ""), "text/markdown".equals(blob.mediaType()));
      var provider = embeddings.getIfAvailable();
      // 向量在写事务之前计算，因此模型调用期间不持有行锁。
      var vectors = provider == null || chunks.isEmpty() ? null : provider.embed(chunks.stream().map(TextChunker.Chunk::text).toList());
      int dimension = vectors == null ? 0 : EmbeddingVectors.validate(vectors, chunks.size());
      boolean written = Boolean.TRUE.equals(tx.execute(status -> {
        if (!repository.holdsLease(job.jobId(), owner)) return false;
        if (provider == null) {
          // 没有向量就不可检索；已就绪的版本保留原索引不动。
          if (repository.isReady(job.versionId())) {
            repository.failWithoutRetry(job.jobId(), job.versionId(), IngestionRepository.REBUILD_FAILED, true);
          } else {
            repository.replaceChunks(job.versionId(), chunks);
            repository.failWithoutRetry(job.jobId(), job.versionId(), "未配置 embedding 模型，无法建立检索索引", false);
          }
        } else {
          var ids = repository.replaceChunks(job.versionId(), chunks);
          repository.insertEmbeddings(ids, vectors, provider.model(), dimension);
          repository.succeed(job.jobId(), job.versionId());
        }
        return true;
      }));
      if (!written) log.warn("Ingestion job {} lost its lease; result discarded", job.jobId());
    } catch (RuntimeException e) {
      log.warn("Ingestion job {} attempt {} failed: {}", job.jobId(), job.attempts(), e.getClass().getSimpleName());
      repository.releaseAfterFailure(job.jobId(), owner, job.attempts() >= settings.maxAttempts(), reason(e), RETRY_BASE_SECONDS * job.attempts());
    }
  }

  private static String reason(RuntimeException e) {
    if (e instanceof IllegalArgumentException) return "文档超出切块上限或格式无法处理";
    return "处理或 embedding 服务失败，稍后重试";
  }
}
