package dev.zhiku.support;

import dev.zhiku.embedding.EmbeddingProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TEST ONLY. Deterministic character/bigram hashing so retrieval tests have real, reproducible vectors.
 * Never registered outside tests; production requires a real embedding model.
 */
public class TestEmbeddingProvider implements EmbeddingProvider {
  public static final int DIMENSION = 64;
  public final AtomicBoolean failing = new AtomicBoolean(false);

  @Override
  public String model() {
    return "test-hash-" + DIMENSION;
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    if (failing.get()) throw new IllegalStateException("embedding service unavailable (test)");
    return texts.stream().map(TestEmbeddingProvider::vector).toList();
  }

  static float[] vector(String text) {
    var v = new float[DIMENSION];
    var cps = text.codePoints().filter(cp -> !Character.isWhitespace(cp)).toArray();
    for (int i = 0; i < cps.length; i++) {
      v[Math.floorMod(cps[i] * 31, DIMENSION)] += 1f;
      if (i + 1 < cps.length) v[Math.floorMod(cps[i] * 131 + cps[i + 1] * 7, DIMENSION)] += 2f;
    }
    v[DIMENSION - 1] += 0.01f;
    return v;
  }
}
