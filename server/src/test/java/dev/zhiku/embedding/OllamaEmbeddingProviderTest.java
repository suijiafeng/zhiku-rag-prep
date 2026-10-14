package dev.zhiku.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 分批向量化在每批之前检查停止信号：摄取失去租约后不再继续调用模型。 */
class OllamaEmbeddingProviderTest {
  HttpServer server;
  final AtomicInteger batches = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/embed", exchange -> {
      batches.incrementAndGet();
      var body = "{\"embeddings\":[[0.1,0.2,0.3]]}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  OllamaEmbeddingProvider provider() {
    return new OllamaEmbeddingProvider(new EmbeddingSettings("ollama", "http://127.0.0.1:" + server.getAddress().getPort(), "test", 1, Duration.ofSeconds(5)));
  }

  @Test
  void embedsBatchByBatch() {
    assertThat(provider().embed(List.of("a", "b", "c"))).hasSize(3);
    assertThat(batches.get()).isEqualTo(3);
  }

  @Test
  void stopsBetweenBatchesOnceTheStopSignalIsRaised() {
    assertThatThrownBy(() -> provider().embed(List.of("a", "b", "c"), () -> batches.get() >= 1)).isInstanceOf(IllegalStateException.class);
    assertThat(batches.get()).isEqualTo(1);
  }
}
