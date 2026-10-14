package dev.zhiku.embedding;

import java.util.List;

/** embedding 模型的端口。查询和 chunk 必须使用同一模型才可比较。 */
public interface EmbeddingProvider {
  String model();

  /** 每个输入对应一个向量，顺序一致。实现必须有超时，且不得记录输入。 */
  List<float[]> embed(List<String> texts);

  /** 长任务分批调用时，在每批之前检查 stop；为真即放弃剩余批次并抛出异常。 */
  default List<float[]> embed(List<String> texts, java.util.function.BooleanSupplier stop) {
    if (stop.getAsBoolean()) throw new IllegalStateException("Embedding stopped");
    return embed(texts);
  }
}
