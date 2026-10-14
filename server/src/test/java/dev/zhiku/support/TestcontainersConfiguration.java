package dev.zhiku.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真实的 PostgreSQL + pgvector，镜像与 compose.yaml 相同。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.0-pg16").asCompatibleSubstituteFor("postgres"));
  }
}
