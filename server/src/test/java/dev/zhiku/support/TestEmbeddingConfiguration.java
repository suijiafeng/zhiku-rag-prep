package dev.zhiku.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestEmbeddingConfiguration {
  @Bean
  TestEmbeddingProvider testEmbeddingProvider() {
    return new TestEmbeddingProvider();
  }
}
