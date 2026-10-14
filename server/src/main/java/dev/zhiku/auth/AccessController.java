package dev.zhiku.auth;

import dev.zhiku.platform.ApiException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 让客户端渲染调用方身份和服务端角色策略，而不是硬编码一份副本。 */
@RestController
class AccessController {
  record Me(UUID userId, UUID enterpriseId, Set<Role> roles, Set<UUID> knowledgeBaseIds) {}

  record Policy(List<Role> roles, List<KnowledgeBaseAction> actions, Map<Role, Map<KnowledgeBaseAction, RolePolicy.Rule>> rules) {}

  private final AccessScopeResolver scopes;

  AccessController(AccessScopeResolver scopes) {
    this.scopes = scopes;
  }

  @GetMapping("/api/me")
  Me me() {
    var scope = scopes.currentScope().orElseThrow(ApiException::unauthenticated);
    return new Me(scope.userId(), scope.enterpriseId(), scope.roles(), scope.knowledgeBaseIds());
  }

  @GetMapping("/api/policy/roles")
  Policy roles() {
    var rules = new EnumMap<Role, Map<KnowledgeBaseAction, RolePolicy.Rule>>(Role.class);
    for (var role : Role.values()) {
      var row = new EnumMap<KnowledgeBaseAction, RolePolicy.Rule>(KnowledgeBaseAction.class);
      for (var action : KnowledgeBaseAction.values()) row.put(action, RolePolicy.ruleFor(role, action));
      rules.put(role, row);
    }
    return new Policy(List.of(Role.values()), List.of(KnowledgeBaseAction.values()), rules);
  }
}
