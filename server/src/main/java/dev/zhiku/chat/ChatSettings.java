package dev.zhiku.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.chat")
public record ChatSettings(String provider, String baseUrl, String model, int contextChunks, int maxChunkChars,
    Duration timeout, Duration streamTimeout, Duration recheckInterval, Duration idleTimeout, int maxAnswerChars,
    Duration heartbeatInterval) {

  public ChatSettings {
    if (recheckInterval == null) recheckInterval = Duration.ofSeconds(1);
    if (idleTimeout == null) idleTimeout = Duration.ofSeconds(30);
    if (maxAnswerChars <= 0) maxAnswerChars = 8000;
    if (heartbeatInterval == null) heartbeatInterval = Duration.ofSeconds(15);
  }
}
