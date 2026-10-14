package dev.zhiku.document;

import dev.zhiku.document.DocumentRepository.CleanupClaim;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 删除已删除文档的内容、chunk、向量和导入任务。文档被标记删除时访问已停止；
 * 这里只回收存储。认领使用 FOR UPDATE SKIP LOCKED 加 lease，与导入相同。
 * 失败以 ERROR 级别记录并显示在文档上；没有外部告警。
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

  /** 最多清理一个文档；返回是否认领到文档。 */
  public boolean processNext() {
    repository.failExhaustedCleanups(MAX_ATTEMPTS);
    var claimed = claim();
    claimed.ifPresent(this::clean);
    return claimed.isPresent();
  }

  public Optional<CleanupClaim> claim() {
    return repository.claimCleanup(LEASE_SECONDS, MAX_ATTEMPTS);
  }

  /** 在此认领下执行清理；lease 已被接管的认领不做任何改变。 */
  public void clean(CleanupClaim claim) {
    try {
      var done = Boolean.TRUE.equals(tx.execute(status -> repository.purgeContent(claim.documentId(), claim.token())));
      if (!done) log.warn("Cleanup of deleted document {} lost its claim; nothing changed", claim.documentId());
    } catch (RuntimeException e) {
      log.error("Cleanup of deleted document {} attempt {} failed: {}", claim.documentId(), claim.attempts(), e.getClass().getSimpleName());
      repository.releaseCleanupAfterFailure(claim.documentId(), claim.token(), claim.attempts() >= MAX_ATTEMPTS, "清理失败，稍后重试");
    }
  }

  /** 轮询等待清理的已删除文档。测试中禁用，测试直接调用 worker。 */
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
        // 逐个认领，处理完所有待清理项
      }
    }
  }
}
