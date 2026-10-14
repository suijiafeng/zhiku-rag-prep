package dev.zhiku.auth;

import java.util.Set;
import java.util.UUID;

/** 纯策略值，不是认证实现。值必须来自可信身份。 */
public record AccessScope(UUID enterpriseId, UUID userId, Set<UUID> knowledgeBaseIds, Set<Role> roles) {
  public AccessScope {
    knowledgeBaseIds = knowledgeBaseIds == null ? Set.of() : Set.copyOf(knowledgeBaseIds);
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** 无角色的范围：可通过知识库检查，但不能执行任何需要角色的操作。 */
  public AccessScope(UUID enterpriseId, UUID userId, Set<UUID> knowledgeBaseIds) {
    this(enterpriseId, userId, knowledgeBaseIds, Set.of());
  }

  public boolean permits(UUID enterprise, UUID knowledgeBase) {
    return enterpriseId != null && userId != null && knowledgeBase != null
        && enterpriseId.equals(enterprise) && knowledgeBaseIds.contains(knowledgeBase);
  }

  public boolean hasRole(Role role) {
    return roles.contains(role);
  }

  public boolean permits(UUID enterprise, UUID knowledgeBase, KnowledgeBaseAction action) {
    return permits(enterprise, knowledgeBase) && RolePolicy.allows(roles, action);
  }
}
