package dev.zhiku.auth;
import java.util.Set;
import java.util.UUID;
/** Pure policy example, not an authentication implementation. Values must come from trusted identity. */
public record AccessScope(UUID enterpriseId, UUID userId, Set<UUID> knowledgeBaseIds) {
 public AccessScope { knowledgeBaseIds = knowledgeBaseIds == null ? Set.of() : Set.copyOf(knowledgeBaseIds); }
 public boolean permits(UUID enterprise, UUID knowledgeBase) {
  return enterpriseId != null && userId != null && knowledgeBase != null
   && enterpriseId.equals(enterprise) && knowledgeBaseIds.contains(knowledgeBase);
 }
}
