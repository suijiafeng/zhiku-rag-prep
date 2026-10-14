package dev.zhiku;

import static dev.zhiku.auth.KnowledgeBaseAction.ASK_AND_CITE;
import static dev.zhiku.auth.KnowledgeBaseAction.DEBUG_AND_EVALUATE;
import static dev.zhiku.auth.KnowledgeBaseAction.MANAGE_DOCUMENTS;
import static dev.zhiku.auth.KnowledgeBaseAction.READ_OTHERS_CONVERSATIONS;
import static dev.zhiku.auth.Role.EMPLOYEE;
import static dev.zhiku.auth.Role.IT_ADMIN;
import static dev.zhiku.auth.Role.KB_OPERATOR;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.auth.RolePolicy;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RolePolicyTest {
  @Test
  void everyRoleMayAskWithinGrantedScope() {
    for (var role : EnumSet.allOf(dev.zhiku.auth.Role.class)) {
      assertTrue(RolePolicy.allows(Set.of(role), ASK_AND_CITE), role.name());
    }
  }

  @Test
  void employeeCannotManageDebugOrReadOthers() {
    assertFalse(RolePolicy.allows(Set.of(EMPLOYEE), MANAGE_DOCUMENTS));
    assertFalse(RolePolicy.allows(Set.of(EMPLOYEE), DEBUG_AND_EVALUATE));
    assertFalse(RolePolicy.allows(Set.of(EMPLOYEE), READ_OTHERS_CONVERSATIONS));
  }

  @Test
  void operatorManagesAndDebugsAssignedKnowledgeBases() {
    assertTrue(RolePolicy.allows(Set.of(KB_OPERATOR), MANAGE_DOCUMENTS));
    assertTrue(RolePolicy.allows(Set.of(KB_OPERATOR), DEBUG_AND_EVALUATE));
  }

  @Test
  void explicitGrantActionsFailClosedUntilModelled() {
    assertFalse(RolePolicy.allows(Set.of(IT_ADMIN), MANAGE_DOCUMENTS));
    assertFalse(RolePolicy.allows(Set.of(IT_ADMIN), DEBUG_AND_EVALUATE));
    assertFalse(RolePolicy.allows(Set.of(KB_OPERATOR), READ_OTHERS_CONVERSATIONS));
  }

  @Test
  void nobodyReadsOthersConversationsByDefault() {
    assertFalse(RolePolicy.allows(EnumSet.allOf(dev.zhiku.auth.Role.class), READ_OTHERS_CONVERSATIONS));
  }

  @Test
  void anyHeldRoleThatAllowsIsEnough() {
    assertTrue(RolePolicy.allows(Set.of(EMPLOYEE, KB_OPERATOR), MANAGE_DOCUMENTS));
  }

  @Test
  void missingRolesOrActionDenied() {
    assertFalse(RolePolicy.allows(Set.of(), ASK_AND_CITE));
    assertFalse(RolePolicy.allows(null, ASK_AND_CITE));
    assertFalse(RolePolicy.allows(Set.of(KB_OPERATOR), null));
  }

  @Test
  void everyActionHasAnExplicitRuleForEveryRole() {
    for (var action : KnowledgeBaseAction.values()) {
      for (var role : dev.zhiku.auth.Role.values()) {
        RolePolicy.ruleFor(role, action);
      }
    }
  }
}
