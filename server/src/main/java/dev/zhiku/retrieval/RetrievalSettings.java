package dev.zhiku.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.retrieval")
public record RetrievalSettings(int defaultK, int maxK) {}
