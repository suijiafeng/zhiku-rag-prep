package dev.zhiku.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 轮询持久化任务表。测试中禁用，测试直接调用 worker。不使用 @Async：任务表就是队列。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zhiku.ingestion.worker-enabled", havingValue = "true")
class IngestionScheduling {
  private final IngestionWorker worker;

  IngestionScheduling(IngestionWorker worker) {
    this.worker = worker;
  }

  /** 一次轮询的任务上限：积压时让出调度线程，其他定时任务才有机会运行。 */
  static final int MAX_JOBS_PER_POLL = 20;

  @Scheduled(fixedDelayString = "${zhiku.ingestion.poll-interval}")
  void poll() {
    for (int n = 0; n < MAX_JOBS_PER_POLL && worker.processNext(); n++) {
      // 逐个认领，最多处理 MAX_JOBS_PER_POLL 个
    }
  }
}
