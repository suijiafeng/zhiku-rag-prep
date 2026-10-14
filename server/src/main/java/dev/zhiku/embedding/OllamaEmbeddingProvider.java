package dev.zhiku.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 本地 Ollama /api/embed。数据不离开本机；输入从不写入日志。 */
@Component
@ConditionalOnProperty(name = "zhiku.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {
  @JsonIgnoreProperties(ignoreUnknown = true)
  record EmbedResponse(List<List<Float>> embeddings) {}

  private final RestClient client;
  private final EmbeddingSettings settings;

  public OllamaEmbeddingProvider(EmbeddingSettings settings) {
    this.settings = settings;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout((int) settings.timeout().toMillis());
    this.client = RestClient.builder().baseUrl(settings.baseUrl()).requestFactory(factory).build();
  }

  @Override
  public String model() {
    return "ollama:" + settings.model();
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    var out = new ArrayList<float[]>(texts.size());
    for (int from = 0; from < texts.size(); from += settings.batchSize()) {
      var batch = texts.subList(from, Math.min(texts.size(), from + settings.batchSize()));
      var response = client.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("model", settings.model(), "input", batch))
          .retrieve().body(EmbedResponse.class);
      if (response == null || response.embeddings() == null || response.embeddings().size() != batch.size()) {
        throw new IllegalStateException("Embedding service returned an unexpected response");
      }
      for (var values : response.embeddings()) {
        var v = new float[values.size()];
        for (int i = 0; i < v.length; i++) v[i] = values.get(i) == null ? Float.NaN : values.get(i);
        out.add(v);
      }
    }
    EmbeddingVectors.validate(out, texts.size());
    return out;
  }
}
