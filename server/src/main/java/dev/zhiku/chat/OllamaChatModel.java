package dev.zhiku.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local Ollama /api/chat streaming (NDJSON). No data leaves the machine; content is never logged. */
@Component
@ConditionalOnProperty(name = "zhiku.chat.provider", havingValue = "ollama")
public class OllamaChatModel implements ChatModel {
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
    try {
      var body = json.writeValueAsString(Map.of("model", settings.model(), "messages", messages, "stream", true,
          "options", Map.of("temperature", 0.1)));
      var request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/api/chat"))
          .timeout(settings.timeout()).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofLines());
      if (response.statusCode() != 200) throw new IllegalStateException("Chat model returned HTTP " + response.statusCode());
      try (var lines = response.body()) {
        var it = lines.iterator();
        while (it.hasNext()) {
          if (cancelled.getAsBoolean()) return;
          var line = it.next();
          if (line.isBlank()) continue;
          var node = json.readTree(line);
          if (node.hasNonNull("error")) throw new IllegalStateException("Chat model reported an error");
          var content = node.path("message").path("content").asText("");
          if (!content.isEmpty()) onToken.accept(content);
          if (node.path("done").asBoolean(false)) return;
        }
      }
      throw new IllegalStateException("Chat model stream ended early");
    } catch (IOException e) {
      throw new IllegalStateException("Chat model is unavailable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Chat model call interrupted", e);
    }
  }
}
