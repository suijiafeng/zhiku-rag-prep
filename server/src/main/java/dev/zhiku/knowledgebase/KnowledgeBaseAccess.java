package dev.zhiku.knowledgebase;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.platform.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single server-side gate used by every knowledge base data entry point.
 * Unknown and unauthorized knowledge bases are both reported as 403.
 */
@Component
public class KnowledgeBaseAccess {
  private final KnowledgeBaseRepository repository;
  private final KnowledgeBaseAuthorization authorization;
  private final AccessScopeResolver scopes;

  public KnowledgeBaseAccess(KnowledgeBaseRepository repository, KnowledgeBaseAuthorization authorization, AccessScopeResolver scopes) {
    this.repository = repository;
    this.authorization = authorization;
    this.scopes = scopes;
  }

  public AccessScope require(UUID kbId, KnowledgeBaseAction action) {
    var ref = repository.find(kbId).orElse(null);
    var decision = ref == null ? null : authorization.decide(ref, action);
    var scope = scopes.currentScope().orElseThrow(ApiException::unauthenticated);
    if (decision == null || !decision.permitted()) throw ApiException.forbidden();
    return scope;
  }

  /** Re-checks access without throwing, for revocation checks during long-running work. */
  public boolean stillPermitted(UUID kbId, KnowledgeBaseAction action) {
    return repository.find(kbId).map(ref -> authorization.decide(ref, action).permitted()).orElse(false);
  }
}
