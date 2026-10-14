package dev.zhiku.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.embedding")
public record EmbeddingSettings(String provider, String baseUrl, String model, int batchSize, Duration timeout) {}
