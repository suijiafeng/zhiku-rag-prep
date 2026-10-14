package dev.zhiku.knowledgebase;

import static dev.zhiku.auth.AuthorizationDecision.DENIED;
import static dev.zhiku.auth.AuthorizationDecision.PERMITTED;
import static dev.zhiku.auth.AuthorizationDecision.UNAUTHENTICATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.AccessScopeResolver;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeBaseAuthorizationTest {
  final UUID enterprise = UUID.randomUUID();
  final UUID kb = UUID.randomUUID();
  final AccessScope scope = new AccessScope(enterprise, UUID.randomUUID(), Set.of(kb));
  final KnowledgeBaseRef granted = new KnowledgeBaseRef(kb, enterprise);

  static KnowledgeBaseAuthorization withScope(AccessScope scope) {
    return new KnowledgeBaseAuthorization(() -> Optional.ofNullable(scope));
  }

  @Test
  void missingServerSideScopeIsUnauthenticated() {
    assertEquals(UNAUTHENTICATED, withScope(null).decide(granted));
  }

  @Test
  void nullResolverResultFailsClosed() {
    assertEquals(UNAUTHENTICATED, new KnowledgeBaseAuthorization(() -> null).decide(granted));
  }

  @Test
  void grantedKnowledgeBaseIsPermitted() {
    assertEquals(PERMITTED, withScope(scope).decide(granted));
  }

  @Test
  void otherEnterpriseDeniedEvenWithSameKnowledgeBaseId() {
    assertEquals(DENIED, withScope(scope).decide(new KnowledgeBaseRef(kb, UUID.randomUUID())));
  }

  @Test
  void ungrantedKnowledgeBaseInSameEnterpriseDenied() {
    assertEquals(DENIED, withScope(scope).decide(new KnowledgeBaseRef(UUID.randomUUID(), enterprise)));
  }

  @Test
  void foreignAndUnknownKnowledgeBasesAreIndistinguishable() {
    var auth = withScope(scope);
    var foreign = auth.decide(new KnowledgeBaseRef(kb, UUID.randomUUID()));
    var unknown = auth.decide(new KnowledgeBaseRef(UUID.randomUUID(), UUID.randomUUID()));
    assertEquals(foreign, unknown);
    assertEquals(DENIED, foreign);
  }

  @Test
  void nullTargetDenied() {
    assertEquals(DENIED, withScope(scope).decide(null));
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
}
