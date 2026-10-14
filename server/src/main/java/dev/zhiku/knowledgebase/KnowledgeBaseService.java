package dev.zhiku.knowledgebase;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.Role;
import dev.zhiku.platform.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {
  private final AccessScopeResolver scopes;
  private final KnowledgeBaseRepository repository;
  private final AuditLog audit;

  public KnowledgeBaseService(AccessScopeResolver scopes, KnowledgeBaseRepository repository, AuditLog audit) {
    this.scopes = scopes;
    this.repository = repository;
    this.audit = audit;
  }

  public AccessScope requireScope() {
    return scopes.currentScope().orElseThrow(ApiException::unauthenticated);
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseView> listGranted() {
    var scope = requireScope();
    return repository.findAll(scope.enterpriseId(), scope.knowledgeBaseIds());
  }

  @Transactional
  public KnowledgeBaseView create(String name) {
    var scope = requireAdmin();
    if (repository.nameTaken(scope.enterpriseId(), name)) throw ApiException.conflict("Knowledge base name already exists");
    var id = UUID.randomUUID();
    repository.insert(id, scope.enterpriseId(), name);
    audit.record(scope, "KB_CREATE", "kb:" + id, "OK", Map.of("name", name));
    return new KnowledgeBaseView(id, name);
  }

  @Transactional
  public void grant(UUID kbId, UUID userId) {
    var scope = requireAdminOf(kbId);
    repository.grant(kbId, userId);
    audit.record(scope, "KB_GRANT", "kb:" + kbId + "/user:" + userId, "OK", Map.of());
  }

  @Transactional
  public void revoke(UUID kbId, UUID userId) {
    var scope = requireAdminOf(kbId);
    repository.revoke(kbId, userId);
    audit.record(scope, "KB_REVOKE", "kb:" + kbId + "/user:" + userId, "OK", Map.of());
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseRepository.AdminView> adminList() {
    return repository.findAllInEnterprise(requireAdmin().enterpriseId());
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseRepository.GrantView> grantsOf(UUID kbId) {
    requireAdminOf(kbId);
    return repository.grants(kbId);
  }

  private AccessScope requireAdmin() {
    var scope = requireScope();
    if (!scope.hasRole(Role.IT_ADMIN)) throw ApiException.forbidden();
    return scope;
  }

  private AccessScope requireAdminOf(UUID kbId) {
    var scope = requireAdmin();
    var kb = repository.find(kbId);
    if (kb.isEmpty() || !kb.get().enterpriseId().equals(scope.enterpriseId())) throw ApiException.forbidden();
    return scope;
  }
}
