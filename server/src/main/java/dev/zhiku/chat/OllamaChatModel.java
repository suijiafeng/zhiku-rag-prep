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
 * Local Ollama /api/chat streaming (NDJSON). No data leaves the machine; content is never logged.
 * Cancellation, the idle timeout and the total timeout reach the HTTP exchange itself: the header wait is polled and
 * a watchdog closes the body, which also ends a read that is blocked in the middle of a line.
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

  /** Waits for response headers while honouring cancellation and the total deadline; null when cancelled. */
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
        // keep polling
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
          // closing is best effort; the reader sees end of stream or an error
        }
      }
    }, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    try {
      var reader = new InputStreamReader(in, StandardCharsets.UTF_8);
      String line;
      while ((line = readLine(reader)) != null) {
        lastData.set(System.nanoTime());
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

  /** One NDJSON line, bounded so a malformed upstream cannot grow a single line without limit; null at end of stream. */
  static String readLine(Reader reader) throws IOException {
    var sb = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      if (c == '\n') return sb.toString();
      if (sb.length() >= MAX_LINE_CHARS) throw new IllegalStateException("Chat model line exceeds the limit");
      sb.append((char) c);
    }
    return sb.isEmpty() ? null : sb.toString();
  }
}
