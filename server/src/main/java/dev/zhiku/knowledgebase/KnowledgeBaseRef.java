package dev.zhiku.knowledgebase;

import java.util.Objects;
import java.util.UUID;

/** 服务端存储的知识库标识；绝不仅凭客户端输入构造。 */
public record KnowledgeBaseRef(UUID id, UUID enterpriseId) {
  public KnowledgeBaseRef {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(enterpriseId, "enterpriseId");
  }
}
