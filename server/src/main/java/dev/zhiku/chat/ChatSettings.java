package dev.zhiku.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.chat")
public record ChatSettings(String provider, String baseUrl, String model, int contextChunks, int maxChunkChars,
    Duration timeout, Duration streamTimeout) {}
