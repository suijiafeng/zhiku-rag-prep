package dev.zhiku.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared Spring context against a real pgvector container. Model providers and CORS are pinned here so a
 * developer's ../.env (imported by application.yml) can never change test behaviour; test doubles stand in.
 */
@SpringBootTest(properties = {"zhiku.chat.provider=none", "zhiku.embedding.provider=none", "zhiku.widget.allowed-origins=",
    "zhiku.chat.recheck-interval=PT0S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEmbeddingConfiguration.class})
public abstract class IntegrationTest {
  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    registry.add("zhiku.auth.jwt-secret", () -> TestJwt.SECRET);
    // Tests drive the ingestion worker explicitly instead of the scheduler.
    registry.add("zhiku.ingestion.worker-enabled", () -> "false");
    registry.add("zhiku.document.cleanup-enabled", () -> "false");
  }
}
