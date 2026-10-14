package dev.zhiku.knowledgebase;

import java.util.Objects;
import java.util.UUID;

/** Identity of a knowledge base as stored server-side; never constructed from client input alone. */
public record KnowledgeBaseRef(UUID id, UUID enterpriseId) {
  public KnowledgeBaseRef {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(enterpriseId, "enterpriseId");
  }
}
