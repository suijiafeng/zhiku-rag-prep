package dev.zhiku.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地 Ollama /api/chat 流式调用（NDJSON）。数据不离开本机；内容从不写入日志。
 * 取消、空闲超时和总超时都作用于 HTTP 交换本身：轮询等待响应头，
 * 并由看门狗关闭正文，这也能结束阻塞在一行中间的读取。
 */
@Component
@ConditionalOnProperty(name = "zhiku.chat.provider", havingValue = "ollama")
public class OllamaChatModel implements ChatModel {
  static final int MAX_LINE_CHARS = 65_536;
  private static final long POLL_MILLIS = 100;
  private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
    var t = new Thread(r, "ollama-watchdog");
    t.setDaemon(true);
    return t;
  });

  private final ChatSettings settings;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OllamaChatModel(ChatSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  @Override
  public String model() {
    return "ollama:" + settings.model();
  }

  @Override
  public void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled) {
    long deadline = System.nanoTime() + settings.timeout().toNanos();
    try {
      var body = json.writeValueAsString(Map.of("model", settings.model(), "messages", messages, "stream", true,
          "options", Map.of("temperature", 0.1)));
      var request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/api/chat"))
          .timeout(settings.timeout()).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response = awaitHeaders(http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()), deadline, cancelled);
      if (response == null) return;
      try (var in = response.body()) {
        if (response.statusCode() != 200) throw new IllegalStateException("Chat model returned HTTP " + response.statusCode());
        read(in, onToken, cancelled, deadline);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Chat model is unavailable", e);
    }
  }

  /** 等待响应头，同时响应取消和总截止时间；取消时返回 null。 */
  private static HttpResponse<InputStream> awaitHeaders(java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> future,
      long deadline, BooleanSupplier cancelled) {
    while (true) {
      if (cancelled.getAsBoolean()) {
        future.cancel(true);
        return null;
      }
      if (System.nanoTime() > deadline) {
        future.cancel(true);
        throw new IllegalStateException("Chat model timed out");
      }
      try {
        return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        // 继续轮询
      } catch (ExecutionException e) {
        throw new IllegalStateException("Chat model is unavailable", e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        throw new IllegalStateException("Chat model call interrupted", e);
      }
    }
  }

  private void read(InputStream in, Consumer<String> onToken, BooleanSupplier cancelled, long deadline) throws IOException {
    var lastData = new AtomicLong(System.nanoTime());
    var stop = new AtomicReference<String>();
    long idle = settings.idleTimeout().toNanos();
    var watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
      long now = System.nanoTime();
      var reason = cancelled.getAsBoolean() ? "cancelled" : now > deadline ? "timed out" : now - lastData.get() > idle ? "idle" : null;
      if (reason != null && stop.compareAndSet(null, reason)) {
        try {
          in.close();
        } catch (IOException ignored) {
          // 关闭只是尽力而为；读取方会看到流结束或错误
        }
      }
    }, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    try {
      var reader = new InputStreamReader(in, StandardCharsets.UTF_8);
      String line;
      // 每收到一个字符就刷新活动时间：空闲指没有数据到达，而不是没有完整的一行。
      while ((line = readLine(reader, () -> lastData.set(System.nanoTime()))) != null) {
        if (stop.get() != null || cancelled.getAsBoolean()) break;
        if (line.isBlank()) continue;
        var node = json.readTree(line);
        if (node.hasNonNull("error")) throw new IllegalStateException("Chat model reported an error");
        var content = node.path("message").path("content").asText("");
        if (!content.isEmpty()) onToken.accept(content);
        if (node.path("done").asBoolean(false)) return;
      }
    } catch (IOException e) {
      if (stop.get() == null) throw e;
    } finally {
      watchdog.cancel(false);
    }
    if ("cancelled".equals(stop.get()) || cancelled.getAsBoolean()) return;
    if (stop.get() != null) throw new IllegalStateException("Chat model stream " + stop.get());
    throw new IllegalStateException("Chat model stream ended early");
  }

  /** 读取一行 NDJSON，有长度上限，防止异常上游让单行无限增长；流结束时返回 null。 */
  static String readLine(Reader reader, Runnable onData) throws IOException {
    var sb = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      onData.run();
      if (c == '\n') return sb.toString();
      if (sb.length() >= MAX_LINE_CHARS) throw new IllegalStateException("Chat model line exceeds the limit");
      sb.append((char) c);
    }
    return sb.isEmpty() ? null : sb.toString();
  }
}
