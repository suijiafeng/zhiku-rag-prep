package dev.zhiku.auth;

import static dev.zhiku.auth.RolePolicy.Rule.ALLOWED;
import static dev.zhiku.auth.RolePolicy.Rule.NEVER;
import static dev.zhiku.auth.RolePolicy.Rule.REQUIRES_EXPLICIT_GRANT;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * “角色权限 AND 知识库范围”中的角色部分（PRD v0.3 第 3 章，待决 D02）。
 * 显式的内容管理和诊断授权尚未建模，因此默认拒绝。
 */
public final class RolePolicy {
  public enum Rule { ALLOWED, REQUIRES_EXPLICIT_GRANT, NEVER }

  private static final Map<Role, Map<KnowledgeBaseAction, Rule>> MATRIX = new EnumMap<>(Role.class);

  static {
    put(Role.IT_ADMIN, ALLOWED, REQUIRES_EXPLICIT_GRANT, REQUIRES_EXPLICIT_GRANT, NEVER);
    put(Role.KB_OPERATOR, ALLOWED, ALLOWED, ALLOWED, REQUIRES_EXPLICIT_GRANT);
    put(Role.EMPLOYEE, ALLOWED, NEVER, NEVER, NEVER);
  }

  private RolePolicy() {}

  /** 顺序：ASK_AND_CITE、MANAGE_DOCUMENTS、DEBUG_AND_EVALUATE、READ_OTHERS_CONVERSATIONS。 */
  private static void put(Role role, Rule ask, Rule manage, Rule debug, Rule readOthers) {
    var rules = new EnumMap<KnowledgeBaseAction, Rule>(KnowledgeBaseAction.class);
    rules.put(KnowledgeBaseAction.ASK_AND_CITE, ask);
    rules.put(KnowledgeBaseAction.MANAGE_DOCUMENTS, manage);
    rules.put(KnowledgeBaseAction.DEBUG_AND_EVALUATE, debug);
    rules.put(KnowledgeBaseAction.READ_OTHERS_CONVERSATIONS, readOthers);
    MATRIX.put(role, rules);
  }

  public static Rule ruleFor(Role role, KnowledgeBaseAction action) {
    var rule = MATRIX.getOrDefault(Objects.requireNonNull(role), Map.of()).get(Objects.requireNonNull(action));
    if (rule == null) {
      throw new IllegalStateException("No rule for " + role + "/" + action);
    }
    return rule;
  }

  /** 仅当持有的某个角色明确为 ALLOWED 时为 true；需显式授权的规则目前一律拒绝。 */
  public static boolean allows(Collection<Role> roles, KnowledgeBaseAction action) {
    if (roles == null || action == null) {
      return false;
    }
    return roles.stream().filter(Objects::nonNull).anyMatch(r -> ruleFor(r, action) == ALLOWED);
  }
}
