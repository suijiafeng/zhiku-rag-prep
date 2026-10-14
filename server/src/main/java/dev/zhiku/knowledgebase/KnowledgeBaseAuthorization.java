package dev.zhiku.knowledgebase;

import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.AuthorizationDecision;
import dev.zhiku.auth.KnowledgeBaseAction;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-level gate for every knowledge base data entry point (query, export, history,
 * citations, file reads). The caller never supplies identity; it comes from the resolver.
 * Role permission and knowledge base scope must both hold (PRD v0.3 chapter 3); the role
 * matrix is the PRD suggestion pending D02. Shared-audience splitting is a data rule, not code.
 */
public final class KnowledgeBaseAuthorization {
  private final AccessScopeResolver resolver;

  public KnowledgeBaseAuthorization(AccessScopeResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  public AuthorizationDecision decide(KnowledgeBaseRef target, KnowledgeBaseAction action) {
    if (target == null || action == null) {
      return AuthorizationDecision.DENIED;
    }
    var scope = Optional.ofNullable(resolver.currentScope()).flatMap(s -> s);
    if (scope.isEmpty()) {
      return AuthorizationDecision.UNAUTHENTICATED;
    }
    return scope.get().permits(target.enterpriseId(), target.id(), action)
        ? AuthorizationDecision.PERMITTED
        : AuthorizationDecision.DENIED;
  }
}
