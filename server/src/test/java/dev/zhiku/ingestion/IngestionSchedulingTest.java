package dev.zhiku.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 一次轮询只处理有限个任务：积压时让出调度线程，删除清理等其他定时任务才有机会运行。 */
class IngestionSchedulingTest {
  @Test
  void onePollProcessesABoundedNumberOfJobs() {
    var settings = new IngestionSettings(1, 800, 100, 100, 300, 3, true, Duration.ofSeconds(2));
    var processed = new AtomicInteger();
    var endless = new IngestionWorker(null, settings, null, null) {
      @Override
      public boolean processNext() {
        processed.incrementAndGet();
        return true;
      }
    };
    new IngestionScheduling(endless).poll();
    assertThat(processed.get()).isEqualTo(IngestionScheduling.MAX_JOBS_PER_POLL);
  }
}
