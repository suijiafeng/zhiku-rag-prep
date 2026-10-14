package dev.zhiku.embedding;

import java.util.List;
import java.util.StringJoiner;

/** 在存储或比较之前校验提供方的输出。 */
public final class EmbeddingVectors {
  private EmbeddingVectors() {}

  /** 返回共同维度；拒绝数量错误、维度不一致、非有限值和零向量。 */
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
