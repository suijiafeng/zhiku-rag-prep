package dev.zhiku.knowledgebase;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.platform.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 每个知识库数据入口共用的唯一服务端闸门。
 * 不存在和未授权的知识库都报告为 403。
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

  /** 不抛异常地重新检查访问权限，用于长时间运行任务中的撤权检查。 */
  public boolean stillPermitted(UUID kbId, KnowledgeBaseAction action) {
    return repository.find(kbId).map(ref -> authorization.decide(ref, action).permitted()).orElse(false);
  }
}
