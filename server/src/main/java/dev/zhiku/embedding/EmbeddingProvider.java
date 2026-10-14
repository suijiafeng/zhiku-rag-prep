package dev.zhiku.embedding;

import java.util.List;

/** embedding 模型的端口。查询和 chunk 必须使用同一模型才可比较。 */
public interface EmbeddingProvider {
  String model();

  /** 每个输入对应一个向量，顺序一致。实现必须有超时，且不得记录输入。 */
  List<float[]> embed(List<String> texts);
}
