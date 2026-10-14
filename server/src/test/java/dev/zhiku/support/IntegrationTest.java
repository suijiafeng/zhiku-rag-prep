package dev.zhiku.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 基于真实 pgvector 容器的共享 Spring 上下文。模型提供方和 CORS 在此固定，使开发者的
 * ../.env（由 application.yml 导入）永远无法改变测试行为；由测试替身代替。
 */
@SpringBootTest(properties = {"zhiku.chat.provider=none", "zhiku.embedding.provider=none", "zhiku.widget.allowed-origins=",
    "zhiku.chat.recheck-interval=PT0S", "zhiku.chat.max-answer-chars=200", "zhiku.chat.handoff-channel="})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEmbeddingConfiguration.class})
public abstract class IntegrationTest {
  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    registry.add("zhiku.auth.jwt-secret", () -> TestJwt.SECRET);
    // 测试显式驱动 ingestion worker，而不是依赖调度器。
    registry.add("zhiku.ingestion.worker-enabled", () -> "false");
    registry.add("zhiku.document.cleanup-enabled", () -> "false");
  }
}
