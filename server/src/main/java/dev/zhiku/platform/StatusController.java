package dev.zhiku.platform;

import dev.zhiku.chat.ChatModel;
import dev.zhiku.document.BusinessSettings;
import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.ingestion.IngestionSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive capability summary: no secrets, URLs, counts of business data or identities. */
@RestController
public class StatusController {
  private final ObjectProvider<ChatModel> chat;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final BusinessSettings business;
  private final IngestionSettings ingestion;

  public StatusController(ObjectProvider<ChatModel> chat, ObjectProvider<EmbeddingProvider> embeddings,
      BusinessSettings business, IngestionSettings ingestion) {
    this.chat = chat;
    this.embeddings = embeddings;
    this.business = business;
    this.ingestion = ingestion;
  }

  @GetMapping("/api/status")
  public Map<String, Object> status() {
    var chatModel = chat.getIfAvailable();
    var embedding = embeddings.getIfAvailable();
    var body = new LinkedHashMap<String, Object>();
    body.put("name", "知库");
    body.put("stage", "development");
    body.put("prdVersion", "0.3");
    body.put("modelEnabled", chatModel != null);
    body.put("chatModel", chatModel == null ? null : chatModel.model());
    body.put("embeddingModel", embedding == null ? null : embedding.model());
    body.put("retrieval", "exact-vector");
    body.put("businessTimeZone", business.timeZone().getId());
    body.put("maxUploadBytes", ingestion.maxBytes());
    body.put("uploadFormats", java.util.List.of(".md", ".markdown", ".txt"));
    return body;
  }
}
