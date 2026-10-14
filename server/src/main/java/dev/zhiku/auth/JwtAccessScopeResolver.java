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
    return Optional.of(new AccessScope(enterprise, user, grants.grantedKnowledgeBases(enterprise, user), roles(jwt.getClaimAsStringList("roles"))));
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
