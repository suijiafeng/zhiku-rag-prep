package dev.zhiku.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Shared Spring context against a real pgvector container. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {
  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    registry.add("zhiku.auth.jwt-secret", () -> TestJwt.SECRET);
  }
}
