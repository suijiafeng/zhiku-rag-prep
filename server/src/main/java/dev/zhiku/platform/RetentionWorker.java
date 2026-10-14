package dev.zhiku.platform;

import dev.zhiku.chat.ChatService;
import dev.zhiku.retrieval.RetrievalService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按保留期删除只用于诊断的数据：检索轨迹（含阶段与候选）和问答请求去重记录。问答记录、反馈与审计不在此清理。
 * 分批删除，避免长事务；每批通过各模块的公开接口完成。
 */
@Component
public class RetentionWorker {
  private static final Logger log = LoggerFactory.getLogger(RetentionWorker.class);

  private final RetentionSettings settings;
  private final RetrievalService retrieval;
  private final ChatService chat;

  public RetentionWorker(RetentionSettings settings, RetrievalService retrieval, ChatService chat) {
    this.settings = settings;
    this.retrieval = retrieval;
    this.chat = chat;
  }

  /** 以 now 为基准删除过期数据；返回删除的轨迹数与请求记录数。 */
  public int[] purge(Instant now) {
    int traces = drain(() -> retrieval.purgeTracesBefore(now.minus(Duration.ofDays(settings.traceDays())), settings.batchSize()));
    int requests = drain(() -> chat.purgeRequestsBefore(now.minus(Duration.ofDays(settings.requestDays())), settings.batchSize()));
    if (traces + requests > 0) log.info("Retention removed {} traces and {} request records", traces, requests);
    return new int[] {traces, requests};
  }

  private int drain(java.util.function.IntSupplier batch) {
    int total = 0;
    int n;
    do {
      n = batch.getAsInt();
      total += n;
    } while (n >= settings.batchSize());
    return total;
  }

  /** 测试中禁用，测试直接调用 worker。 */
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(name = "zhiku.retention.enabled", havingValue = "true")
  static class Scheduling {
    private final RetentionWorker worker;

    Scheduling(RetentionWorker worker) {
      this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${zhiku.retention.poll-interval}", initialDelayString = "PT1M")
    void poll() {
      try {
        worker.purge(Instant.now());
      } catch (RuntimeException e) {
        log.error("Retention purge failed: {}", e.getClass().getSimpleName());
      }
    }
  }
}
