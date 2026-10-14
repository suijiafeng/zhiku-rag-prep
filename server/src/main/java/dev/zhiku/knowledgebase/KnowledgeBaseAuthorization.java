package dev.zhiku.knowledgebase;

import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.AuthorizationDecision;
import dev.zhiku.auth.KnowledgeBaseAction;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 每个知识库数据入口（查询、导出、历史、引用、文件读取）
 * 的应用层闸门。调用方从不提供身份；身份来自 resolver。
 * 角色权限和知识库范围必须同时满足（PRD v0.3 第 3 章）；角色
 * 矩阵为 PRD 建议，待决 D02。共享受众的拆分是数据规则，不是代码。
 */
@Component
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
