package dev.zhiku.document;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes content, chunks, vectors and ingestion jobs of deleted documents. Access already stopped when the document
 * was marked deleted; this only reclaims storage. Claims use FOR UPDATE SKIP LOCKED plus a lease, like ingestion.
 * Failures are logged at ERROR and shown on the document; there is no external alerting.
 */
@Component
public class DocumentCleanupWorker {
  static final int LEASE_SECONDS = 300;
  static final int MAX_ATTEMPTS = 3;
  private static final Logger log = LoggerFactory.getLogger(DocumentCleanupWorker.class);

  private final DocumentRepository repository;
  private final TransactionTemplate tx;

  public DocumentCleanupWorker(DocumentRepository repository, TransactionTemplate tx) {
    this.repository = repository;
    this.tx = tx;
  }

  /** Cleans at most one document; returns whether one was claimed. */
  public boolean processNext() {
    repository.failExhaustedCleanups(MAX_ATTEMPTS);
    Optional<UUID> claimed = repository.claimCleanup(LEASE_SECONDS, MAX_ATTEMPTS);
    claimed.ifPresent(this::clean);
    return claimed.isPresent();
  }

  private void clean(UUID documentId) {
    try {
      tx.executeWithoutResult(status -> repository.purgeContent(documentId));
    } catch (RuntimeException e) {
      var attempts = repository.cleanupAttempts(documentId);
      log.error("Cleanup of deleted document {} attempt {} failed: {}", documentId, attempts, e.getClass().getSimpleName());
      repository.releaseCleanupAfterFailure(documentId, attempts >= MAX_ATTEMPTS, "清理失败，稍后重试");
    }
  }

  /** Polls for deleted documents awaiting cleanup. Disabled in tests, which call the worker directly. */
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(name = "zhiku.document.cleanup-enabled", havingValue = "true")
  static class Scheduling {
    private final DocumentCleanupWorker worker;

    Scheduling(DocumentCleanupWorker worker) {
      this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${zhiku.document.cleanup-poll-interval}")
    void poll() {
      while (worker.processNext()) {
        // drain pending cleanups, one claim at a time
      }
    }
  }
}
