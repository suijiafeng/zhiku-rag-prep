package dev.zhiku.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 诊断数据的保留期。保留天数是部署默认值，正式保留期待业务确认（ADR-002）。 */
@ConfigurationProperties("zhiku.retention")
public record RetentionSettings(boolean enabled, Duration pollInterval, int traceDays, int requestDays, int batchSize) {

  public RetentionSettings {
    if (pollInterval == null) pollInterval = Duration.ofHours(1);
    if (traceDays <= 0) traceDays = 90;
    if (requestDays <= 0) requestDays = 7;
    if (batchSize <= 0) batchSize = 1000;
  }
}
