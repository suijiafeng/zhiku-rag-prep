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
 * 根据已验证的 token（企业、用户、角色）和授权存储构建访问范围。
 * 除已验证的 bearer token 外，从不参考请求体和其他请求头。
 */
@Component
public class JwtAccessScopeResolver implements AccessScopeResolver {
  /** Widget token：仅限所列知识库和 chat 端点。 */
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
