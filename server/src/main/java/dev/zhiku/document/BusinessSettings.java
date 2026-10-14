package dev.zhiku.document;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Business dates are entered in the configured zone and stored as UTC instants; intervals are [from, until). */
@ConfigurationProperties("zhiku.business")
public record BusinessSettings(ZoneId timeZone) {
  public Instant startOf(LocalDate date) {
    return date.atStartOfDay(timeZone).toInstant();
  }
}
