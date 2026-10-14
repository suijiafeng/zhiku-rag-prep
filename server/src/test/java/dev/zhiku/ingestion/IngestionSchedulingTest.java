package dev.zhiku.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.ingestion.IngestionRepository.ClaimedJob;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 摄取用有界的并发槽处理任务：一个卡住的任务只占一个槽，其他文档照常完成；槽满时不再认领。 */
class IngestionSchedulingTest {
  static IngestionSettings settings(int concurrency) {
    return new IngestionSettings(1, 800, 100, 100, 300, 3, true, Duration.ofSeconds(2), concurrency);
  }

  /** 以内存队列代替任务表的 worker：第一个任务阻塞到 latch 放开，其余立即完成。 */
  static class FakeWorker extends IngestionWorker {
    final Queue<ClaimedJob> queue = new ArrayDeque<>();
    final Queue<UUID> done = new ConcurrentLinkedQueue<>();
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch blocked = new CountDownLatch(1);
    final AtomicInteger reaped = new AtomicInteger();
    final UUID stuck;

    FakeWorker(int jobs) {
      super(null, settings(1), null, null);
      stuck = UUID.randomUUID();
      queue.add(new ClaimedJob(stuck, UUID.randomUUID(), 1));
      for (int i = 1; i < jobs; i++) queue.add(new ClaimedJob(UUID.randomUUID(), UUID.randomUUID(), 1));
    }

    @Override
    public void reapExpired() {
      reaped.incrementAndGet();
    }

    @Override
    public synchronized Optional<ClaimedJob> claim() {
      return Optional.ofNullable(queue.poll());
    }

    @Override
    public void process(ClaimedJob job) {
      if (job.jobId().equals(stuck)) {
        blocked.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      done.add(job.jobId());
    }
  }

  IngestionScheduling scheduling;

  @AfterEach
  void stop() {
    if (scheduling != null) scheduling.shutdown();
  }

  @Test
  void aStuckJobDoesNotHoldUpTheOtherDocuments() throws Exception {
    var worker = new FakeWorker(5);
    scheduling = new IngestionScheduling(worker, settings(2));
    scheduling.poll();
    assertThat(worker.blocked.await(5, TimeUnit.SECONDS)).isTrue();
    waitUntil(() -> worker.done.size() == 4);
    assertThat(worker.done).hasSize(4).doesNotContain(worker.stuck);
    assertThat(worker.reaped.get()).isEqualTo(1);
    worker.release.countDown();
    waitUntil(() -> worker.done.size() == 5);
    assertThat(worker.done).contains(worker.stuck);
  }

  @Test
  void aPollClaimsNothingWhileEverySlotIsBusy() throws Exception {
    var worker = new FakeWorker(3);
    scheduling = new IngestionScheduling(worker, settings(1));
    scheduling.poll();
    assertThat(worker.blocked.await(5, TimeUnit.SECONDS)).isTrue();
    scheduling.poll();
    assertThat(worker.queue).hasSize(2);
    assertThat(worker.done).isEmpty();
    worker.release.countDown();
    waitUntil(() -> worker.done.size() == 3);
    assertThat(List.copyOf(worker.done)).hasSize(3);
  }

  @Test
  void oneSlotProcessesABoundedNumberOfJobsPerPoll() throws Exception {
    var worker = new FakeWorker(IngestionScheduling.MAX_JOBS_PER_POLL + 5);
    scheduling = new IngestionScheduling(worker, settings(1));
    worker.release.countDown();
    scheduling.poll();
    waitUntil(() -> worker.done.size() == IngestionScheduling.MAX_JOBS_PER_POLL);
    Thread.sleep(50);
    assertThat(worker.queue).hasSize(5);
    scheduling.poll();
    waitUntil(() -> worker.done.size() == IngestionScheduling.MAX_JOBS_PER_POLL + 5);
  }

  static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("condition not met in time");
      Thread.sleep(10);
    }
  }
}
