package dev.zhiku.knowledgebase;

import static dev.zhiku.auth.AuthorizationDecision.DENIED;
import static dev.zhiku.auth.AuthorizationDecision.PERMITTED;
import static dev.zhiku.auth.AuthorizationDecision.UNAUTHENTICATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.AccessScopeResolver;
import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.auth.Role;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeBaseAuthorizationTest {
  final UUID enterprise = UUID.randomUUID();
  final UUID kb = UUID.randomUUID();
  final AccessScope scope = new AccessScope(enterprise, UUID.randomUUID(), Set.of(kb), Set.of(Role.EMPLOYEE));
  static final KnowledgeBaseAction ASK = KnowledgeBaseAction.ASK_AND_CITE;
  final KnowledgeBaseRef granted = new KnowledgeBaseRef(kb, enterprise);

  static KnowledgeBaseAuthorization withScope(AccessScope scope) {
    return new KnowledgeBaseAuthorization(() -> Optional.ofNullable(scope));
  }

  @Test
  void missingServerSideScopeIsUnauthenticated() {
    assertEquals(UNAUTHENTICATED, withScope(null).decide(granted, ASK));
  }

  @Test
  void nullResolverResultFailsClosed() {
    assertEquals(UNAUTHENTICATED, new KnowledgeBaseAuthorization(() -> null).decide(granted, ASK));
  }

  @Test
  void grantedKnowledgeBaseIsPermitted() {
    assertEquals(PERMITTED, withScope(scope).decide(granted, ASK));
  }

  @Test
  void otherEnterpriseDeniedEvenWithSameKnowledgeBaseId() {
    assertEquals(DENIED, withScope(scope).decide(new KnowledgeBaseRef(kb, UUID.randomUUID()), ASK));
  }

  @Test
  void ungrantedKnowledgeBaseInSameEnterpriseDenied() {
    assertEquals(DENIED, withScope(scope).decide(new KnowledgeBaseRef(UUID.randomUUID(), enterprise), ASK));
  }

  @Test
  void foreignAndUnknownKnowledgeBasesAreIndistinguishable() {
    var auth = withScope(scope);
    var foreign = auth.decide(new KnowledgeBaseRef(kb, UUID.randomUUID()), ASK);
    var unknown = auth.decide(new KnowledgeBaseRef(UUID.randomUUID(), UUID.randomUUID()), ASK);
    assertEquals(foreign, unknown);
    assertEquals(DENIED, foreign);
  }

  @Test
  void nullTargetDenied() {
    assertEquals(DENIED, withScope(scope).decide(null, ASK));
  }

  @Test
  void refRequiresBothIdentifiers() {
    assertThrows(NullPointerException.class, () -> new KnowledgeBaseRef(null, enterprise));
    assertThrows(NullPointerException.class, () -> new KnowledgeBaseRef(kb, null));
  }

  @Test
  void resolverIsMandatory() {
    assertThrows(NullPointerException.class, () -> new KnowledgeBaseAuthorization(null));
  }

  @Test
  void roleAndScopeMustBothHold() {
    var auth = withScope(scope);
    assertEquals(DENIED, auth.decide(granted, KnowledgeBaseAction.MANAGE_DOCUMENTS));
    var operatorElsewhere = new AccessScope(enterprise, UUID.randomUUID(), Set.of(UUID.randomUUID()), Set.of(Role.KB_OPERATOR));
    assertEquals(DENIED, withScope(operatorElsewhere).decide(granted, KnowledgeBaseAction.MANAGE_DOCUMENTS));
    var operatorHere = new AccessScope(enterprise, UUID.randomUUID(), Set.of(kb), Set.of(Role.KB_OPERATOR));
    assertEquals(PERMITTED, withScope(operatorHere).decide(granted, KnowledgeBaseAction.MANAGE_DOCUMENTS));
  }

  @Test
  void scopeWithoutRolesIsDenied() {
    var noRoles = new AccessScope(enterprise, UUID.randomUUID(), Set.of(kb));
    assertEquals(DENIED, withScope(noRoles).decide(granted, ASK));
  }

  @Test
  void nullActionDenied() {
    assertEquals(DENIED, withScope(scope).decide(granted, null));
  }
}
