package dev.zhiku.auth;

import java.util.Optional;

/**
 * Application port for the identity of the current request.
 * Infrastructure must build the scope from server-side authentication only; request DTOs,
 * headers chosen by the browser or widget parameters are never an acceptable source.
 * No production implementation exists until the identity source in ADR-002 is confirmed.
 */
@FunctionalInterface
public interface AccessScopeResolver {
  /** Empty when no trusted identity exists. Callers must treat null as empty. */
  Optional<AccessScope> currentScope();
}
