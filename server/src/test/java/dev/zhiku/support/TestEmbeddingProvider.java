package dev.zhiku.support;

import dev.zhiku.embedding.EmbeddingProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅用于测试。确定性的字符/二元组哈希，使检索测试拥有真实、可复现的向量。
 * 绝不在测试之外注册；生产环境需要真实的 embedding 模型。
 */
public class TestEmbeddingProvider implements EmbeddingProvider {
  public static final int DIMENSION = 64;
  public final AtomicBoolean failing = new AtomicBoolean(false);
  /** 在每次 embedding 调用前运行，例如在查询正在 embedding 时提交一次删除。 */
  public volatile Runnable beforeEmbed = () -> {};
  /** 因停止信号而中止的调用次数。 */
  public final java.util.concurrent.atomic.AtomicInteger stoppedCalls = new java.util.concurrent.atomic.AtomicInteger();

  @Override
  public String model() {
    return "test-hash-" + DIMENSION;
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    return embed(texts, () -> false);
  }

  @Override
  public List<float[]> embed(List<String> texts, java.util.function.BooleanSupplier stop) {
    beforeEmbed.run();
    if (stop.getAsBoolean()) {
      stoppedCalls.incrementAndGet();
      throw new IllegalStateException("embedding stopped (test)");
    }
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
