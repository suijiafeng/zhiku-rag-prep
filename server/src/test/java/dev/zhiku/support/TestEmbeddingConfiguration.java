package dev.zhiku.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 仅测试用的模型替身；生产 bean 由 zhiku.embedding.provider / zhiku.chat.provider 选择。 */
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
