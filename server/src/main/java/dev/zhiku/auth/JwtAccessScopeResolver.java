package dev.zhiku.auth;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Builds the access scope from the verified token (enterprise, user, roles) and the grant store.
 * Request bodies and headers other than the verified bearer token are never consulted.
 */
@Component
public class JwtAccessScopeResolver implements AccessScopeResolver {
  /** Widget tokens: restricted to the listed knowledge bases and to the chat endpoint. */
  public static final String KB_SCOPE = "kb_scope";
  public static final String TOKEN_USE = "token_use";
  public static final String WIDGET = "widget";

  private final GrantLookup grants;

  public JwtAccessScopeResolver(GrantLookup grants) {
    this.grants = grants;
  }

  @Override
  public Optional<AccessScope> currentScope() {
    if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()) {
      return Optional.empty();
    }
    var jwt = auth.getToken();
    UUID user = uuid(jwt.getSubject());
    UUID enterprise = uuid(jwt.getClaimAsString("enterprise_id"));
    if (user == null || enterprise == null) {
      return Optional.empty();
    }
    var granted = new java.util.HashSet<>(grants.grantedKnowledgeBases(enterprise, user));
    if (WIDGET.equals(jwt.getClaimAsString(TOKEN_USE)) || jwt.hasClaim(KB_SCOPE)) {
      var limit = jwt.getClaimAsStringList(KB_SCOPE);
      granted.removeIf(kb -> limit == null || !limit.contains(kb.toString()));
    }
    return Optional.of(new AccessScope(enterprise, user, granted, roles(jwt.getClaimAsStringList("roles"))));
  }

  private static UUID uuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Set<Role> roles(List<String> names) {
    var roles = EnumSet.noneOf(Role.class);
    if (names != null) {
      for (var name : names) {
        for (var role : Role.values()) {
          if (role.name().equals(name)) roles.add(role);
        }
      }
    }
    return roles;
  }
}
