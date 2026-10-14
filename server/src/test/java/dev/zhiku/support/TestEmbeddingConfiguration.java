package dev.zhiku.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only model doubles; production beans are selected by zhiku.embedding.provider / zhiku.chat.provider. */
@TestConfiguration(proxyBeanMethods = false)
public class TestEmbeddingConfiguration {
  @Bean
  TestEmbeddingProvider testEmbeddingProvider() {
    return new TestEmbeddingProvider();
  }

  @Bean
  TestChatModel testChatModel() {
    return new TestChatModel();
  }
}
