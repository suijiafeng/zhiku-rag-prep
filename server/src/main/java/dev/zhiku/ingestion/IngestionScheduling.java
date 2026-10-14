package dev.zhiku.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls the durable job table. Disabled in tests, which call the worker directly. Not @Async: the table is the queue. */
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
      // drain available jobs, one claim at a time
    }
  }
}
