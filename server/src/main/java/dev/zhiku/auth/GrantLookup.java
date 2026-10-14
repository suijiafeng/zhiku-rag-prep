package dev.zhiku.auth;

import java.util.Set;
import java.util.UUID;

/** Port: knowledge bases currently granted to a user, read on every request so revocation is immediate. */
public interface GrantLookup {
  Set<UUID> grantedKnowledgeBases(UUID enterpriseId, UUID userId);
}
