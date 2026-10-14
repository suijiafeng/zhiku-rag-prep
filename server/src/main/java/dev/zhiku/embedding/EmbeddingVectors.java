package dev.zhiku.embedding;

import java.util.List;
import java.util.StringJoiner;

/** Validates provider output before it is stored or compared. */
public final class EmbeddingVectors {
  private EmbeddingVectors() {}

  /** Returns the common dimension; rejects wrong counts, mixed dimensions, non-finite values and zero vectors. */
  public static int validate(List<float[]> vectors, int expectedCount) {
    if (vectors == null || vectors.size() != expectedCount) throw new IllegalStateException("Embedding count mismatch");
    int dimension = -1;
    for (var v : vectors) {
      if (v == null || v.length == 0 || (dimension >= 0 && v.length != dimension)) throw new IllegalStateException("Embedding dimension mismatch");
      dimension = v.length;
      double norm = 0;
      for (float x : v) {
        if (!Float.isFinite(x)) throw new IllegalStateException("Embedding values must be finite");
        norm += (double) x * x;
      }
      if (norm == 0) throw new IllegalStateException("Embedding must not be a zero vector");
    }
    return dimension;
  }

  public static String literal(float[] v) {
    var joiner = new StringJoiner(",", "[", "]");
    for (float x : v) joiner.add(Float.toString(x));
    return joiner.toString();
  }
}
