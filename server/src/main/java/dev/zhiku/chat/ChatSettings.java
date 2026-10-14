package dev.zhiku.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.chat")
public record ChatSettings(String provider, String baseUrl, String model, int contextChunks, int maxChunkChars,
    Duration timeout, Duration streamTimeout, Duration recheckInterval) {

  public ChatSettings {
    if (recheckInterval == null) recheckInterval = Duration.ofSeconds(1);
  }
}
