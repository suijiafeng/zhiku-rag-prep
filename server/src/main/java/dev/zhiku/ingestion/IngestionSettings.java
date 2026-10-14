package dev.zhiku.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.ingestion")
public record IngestionSettings(long maxBytes, int chunkMaxChars, int chunkOverlap, int maxChunks, int leaseSeconds,
    int maxAttempts, boolean workerEnabled, Duration pollInterval, int concurrency) {

  public IngestionSettings {
    if (concurrency <= 0) concurrency = 1;
  }
}
