package dev.zhiku.ingestion;

import dev.zhiku.ingestion.IngestionRepository.ClaimedJob;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes durable ingestion jobs. Claiming uses FOR UPDATE SKIP LOCKED plus a lease; results are written
 * in one transaction only while the lease is still held, so a crashed or slow worker never duplicates output.
 */
@Component
public class IngestionWorker {
  private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

  private final IngestionRepository repository;
  private final IngestionSettings settings;
  private final TransactionTemplate tx;
  private final TextChunker chunker;
  private final String owner = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID();

  public IngestionWorker(IngestionRepository repository, IngestionSettings settings, TransactionTemplate tx) {
    this.repository = repository;
    this.settings = settings;
    this.tx = tx;
    this.chunker = new TextChunker(settings.chunkMaxChars(), settings.chunkOverlap(), settings.maxChunks());
  }

  /** Processes at most one job; returns whether a job was claimed. */
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
      boolean written = Boolean.TRUE.equals(tx.execute(status -> {
        if (!repository.holdsLease(job.jobId(), owner)) return false;
        repository.replaceChunks(job.versionId(), chunks);
        repository.succeed(job.jobId(), job.versionId());
        return true;
      }));
      if (!written) log.warn("Ingestion job {} lost its lease; result discarded", job.jobId());
    } catch (RuntimeException e) {
      log.warn("Ingestion job {} attempt {} failed: {}", job.jobId(), job.attempts(), e.getClass().getSimpleName());
      repository.releaseAfterFailure(job.jobId(), job.versionId(), owner, job.attempts() >= settings.maxAttempts(), reason(e));
    }
  }

  private static String reason(RuntimeException e) {
    return e instanceof IllegalArgumentException ? "文档超出切块上限或格式无法处理" : "处理失败，稍后重试";
  }
}
