package dev.zhiku.knowledgebase;

import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.AuthorizationDecision;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-level gate for every knowledge base data entry point (query, export, history,
 * citations, file reads). The caller never supplies identity; it comes from the resolver.
 * Role rules and shared-audience splitting are not implemented pending ADR-002 inputs.
 */
public final class KnowledgeBaseAuthorization {
  private final AccessScopeResolver resolver;

  public KnowledgeBaseAuthorization(AccessScopeResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  public AuthorizationDecision decide(KnowledgeBaseRef target) {
    if (target == null) {
      return AuthorizationDecision.DENIED;
    }
    var scope = Optional.ofNullable(resolver.currentScope()).flatMap(s -> s);
    if (scope.isEmpty()) {
      return AuthorizationDecision.UNAUTHENTICATED;
    }
    return scope.get().permits(target.enterpriseId(), target.id())
        ? AuthorizationDecision.PERMITTED
        : AuthorizationDecision.DENIED;
  }
}
