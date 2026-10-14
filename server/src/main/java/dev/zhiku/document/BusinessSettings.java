package dev.zhiku.document;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务日期按配置时区录入，以 UTC 时刻存储；区间为 [from, until)。 */
@ConfigurationProperties("zhiku.business")
public record BusinessSettings(ZoneId timeZone) {
  public Instant startOf(LocalDate date) {
    return date.atStartOfDay(timeZone).toInstant();
  }
}
