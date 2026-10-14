package dev.zhiku.embedding;

import java.util.List;

/** Port for an embedding model. Queries and chunks must use the same model to be comparable. */
public interface EmbeddingProvider {
  String model();

  /** One vector per input, in order. Implementations must time out and must not log inputs. */
  List<float[]> embed(List<String> texts);
}
