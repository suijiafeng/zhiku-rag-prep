package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 在 loopback 上用假 Ollama 测试适配器：取消、空闲超时和总超时必须能结束阻塞在读取中
 * 或仍在等待响应头的流，错误响应不得等待其正文。
 */
class OllamaChatModelTest {
  interface Behaviour {
    void handle(HttpExchange exchange) throws Exception;
  }

  HttpServer server;
  volatile Behaviour behaviour;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/api/chat", exchange -> {
      try {
        behaviour.handle(exchange);
      } catch (Exception e) {
        // 客户端已关闭连接；无需报告
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  OllamaChatModel model(Duration total, Duration idle) {
    var settings = new ChatSettings("ollama", "http://127.0.0.1:" + server.getAddress().getPort(), "test", 5, 1500,
        total, Duration.ofSeconds(150), Duration.ofSeconds(1), idle, 8000, Duration.ofSeconds(15), "");
    return new OllamaChatModel(settings, new ObjectMapper());
  }

  static void write(HttpExchange exchange, String text) throws IOException {
    exchange.getResponseBody().write(text.getBytes(StandardCharsets.UTF_8));
    exchange.getResponseBody().flush();
  }

  static String piece(String content, boolean done) {
    return "{\"message\":{\"content\":\"" + content + "\"},\"done\":" + done + "}\n";
  }

  static List<ChatModel.Message> prompt() {
    return List.of(new ChatModel.Message("user", "问题"));
  }

  @Test
  void streamsPiecesUntilDone() {
    behaviour = exchange -> {
      exchange.sendResponseHeaders(200, 0);
      write(exchange, piece("年假", false) + piece("按司龄[1]", false) + piece("", true));
    };
    var pieces = new ArrayList<String>();
    model(Duration.ofSeconds(5), Duration.ofSeconds(5)).stream(prompt(), pieces::add, () -> false);
    assertThat(pieces).containsExactly("年假", "按司龄[1]");
  }

  @Test
  void cancellationEndsAReadBlockedInTheMiddleOfALine() {
    behaviour = exchange -> {
      exchange.sendResponseHeaders(200, 0);
      write(exchange, piece("年假", false) + "{\"message\":{\"content\":\"按");
      Thread.sleep(5000);
    };
    long started = System.nanoTime();
    var pieces = new ArrayList<String>();
    model(Duration.ofSeconds(30), Duration.ofSeconds(30)).stream(prompt(), pieces::add, () -> System.nanoTime() - started > 200_000_000L);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    assertThat(pieces).containsExactly("年假");
  }

  @Test
  void cancellationEndsTheWaitForResponseHeaders() {
    behaviour = exchange -> Thread.sleep(5000);
    long started = System.nanoTime();
    model(Duration.ofSeconds(30), Duration.ofSeconds(30)).stream(prompt(), p -> {}, () -> System.nanoTime() - started > 200_000_000L);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  void anIdleStreamFailsAfterTheIdleTimeout() {
    behaviour = exchange -> {
      exchange.sendResponseHeaders(200, 0);
      write(exchange, piece("年假", false));
      Thread.sleep(5000);
    };
    long started = System.nanoTime();
    assertThatThrownBy(() -> model(Duration.ofSeconds(30), Duration.ofMillis(300)).stream(prompt(), p -> {}, () -> false))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  void aSteadyStreamStillEndsAtTheTotalDeadline() {
    behaviour = exchange -> {
      exchange.sendResponseHeaders(200, 0);
      for (int i = 0; i < 100; i++) {
        write(exchange, piece("字", false));
        Thread.sleep(50);
      }
    };
    long started = System.nanoTime();
    assertThatThrownBy(() -> model(Duration.ofMillis(500), Duration.ofSeconds(30)).stream(prompt(), p -> {}, () -> false))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  void anErrorStatusFailsWithoutWaitingForItsBody() {
    behaviour = exchange -> {
      exchange.sendResponseHeaders(503, 0);
      write(exchange, "{\"error\":\"busy");
      Thread.sleep(5000);
    };
    long started = System.nanoTime();
    assertThatThrownBy(() -> model(Duration.ofSeconds(30), Duration.ofSeconds(30)).stream(prompt(), p -> {}, () -> false))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("503");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  void anOverlongLineIsRejected() {
    behaviour = exchange -> {
      exchange.sendResponseHeaders(200, 0);
      write(exchange, "{\"message\":{\"content\":\"" + "长".repeat(70_000) + "\"},\"done\":false}\n");
    };
    assertThatThrownBy(() -> model(Duration.ofSeconds(5), Duration.ofSeconds(5)).stream(prompt(), p -> {}, () -> false))
        .isInstanceOf(IllegalStateException.class);
  }
}
