package dev.zhiku.auth;

import java.util.Set;
import java.util.UUID;

/** Pure policy value, not an authentication implementation. Values must come from trusted identity. */
public record AccessScope(UUID enterpriseId, UUID userId, Set<UUID> knowledgeBaseIds, Set<Role> roles) {
  public AccessScope {
    knowledgeBaseIds = knowledgeBaseIds == null ? Set.of() : Set.copyOf(knowledgeBaseIds);
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** Scope without roles: may pass the knowledge base check but no role-gated action. */
  public AccessScope(UUID enterpriseId, UUID userId, Set<UUID> knowledgeBaseIds) {
    this(enterpriseId, userId, knowledgeBaseIds, Set.of());
  }

  public boolean permits(UUID enterprise, UUID knowledgeBase) {
    return enterpriseId != null && userId != null && knowledgeBase != null
        && enterpriseId.equals(enterprise) && knowledgeBaseIds.contains(knowledgeBase);
  }

  public boolean permits(UUID enterprise, UUID knowledgeBase, KnowledgeBaseAction action) {
    return permits(enterprise, knowledgeBase) && RolePolicy.allows(roles, action);
  }
}
