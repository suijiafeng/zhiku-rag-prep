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

  @Scheduled(fixedDelayString = "${zhiku.ingestion.poll-interval}")
  void poll() {
    while (worker.processNext()) {
      // 逐个认领，处理完所有可用任务
    }
  }
}
