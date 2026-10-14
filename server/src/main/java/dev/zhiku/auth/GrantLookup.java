package dev.zhiku.auth;

import java.util.Set;
import java.util.UUID;

/** 端口：用户当前被授权的知识库，每次请求都读取，使撤权立即生效。 */
public interface GrantLookup {
  Set<UUID> grantedKnowledgeBases(UUID enterpriseId, UUID userId);
}
