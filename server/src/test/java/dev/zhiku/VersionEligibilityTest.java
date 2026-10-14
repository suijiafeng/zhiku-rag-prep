package dev.zhiku;
import dev.zhiku.auth.AccessScope;
import dev.zhiku.document.VersionEligibility;
import dev.zhiku.document.VersionEligibility.Version;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.zhiku.document.VersionEligibility.Outcome.*;
class VersionEligibilityTest {
 final UUID e=UUID.randomUUID(), k=UUID.randomUUID();
 final Instant start=Instant.parse("2026-01-01T00:00:00Z"), end=start.plusSeconds(60);
 final AccessScope scope=new AccessScope(e, UUID.randomUUID(), Set.of(k));
 Version version(boolean ready, Instant from, Instant until) { return new Version(e,k,true,true,false,false,ready,from,until); }
 @Test void inclusiveStartExclusiveEnd() { var v=version(true,start,end); assertEquals(ELIGIBLE,VersionEligibility.evaluate(scope,List.of(v),start)); assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(scope,List.of(v),end)); }
 @Test void futureAndCurrentCoexist() { assertEquals(ELIGIBLE,VersionEligibility.evaluate(scope,List.of(version(true,start,end),version(true,end,null)),start)); }
 @Test void unreadyReplacementBlocksOldFallback() { assertEquals(INDEX_NOT_READY,VersionEligibility.evaluate(scope,List.of(version(true,start,end),version(false,end,null)),end)); }
 @Test void conflictsFailClosed() { assertEquals(CONFLICT,VersionEligibility.evaluate(scope,List.of(version(true,start,end),version(true,start,null)),start)); }
 @Test void missingScopeFailsClosed() { assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(null,List.of(version(true,start,end)),start)); }
 @Test void otherEnterpriseDenied() { var other=new AccessScope(UUID.randomUUID(),UUID.randomUUID(),Set.of(k)); assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(other,List.of(version(true,start,end)),start)); }
 @Test void otherKnowledgeBaseDenied() { assertFalse(scope.permits(e,UUID.randomUUID())); }
 @Test void approvalSeparateFromIndex() { var v=new Version(e,k,false,true,false,false,true,start,end); assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(scope,List.of(v),start)); }
 @Test void withdrawnDenied() { var v=new Version(e,k,true,true,true,false,true,start,end); assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(scope,List.of(v),start)); }
 @Test void invalidWindowRejected() { assertThrows(IllegalArgumentException.class,()->version(true,end,start)); }
 @Test void unpublishedDenied() { var v=new Version(e,k,true,false,false,false,true,start,end); assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(scope,List.of(v),start)); }
 @Test void supersededDenied() { var v=new Version(e,k,true,true,false,true,true,start,end); assertEquals(NO_EVIDENCE,VersionEligibility.evaluate(scope,List.of(v),start)); }
 @Test void identityMissingDenied() { assertFalse(new AccessScope(e,null,Set.of(k)).permits(e,k)); }
 @Test void missingGrantsDenied() { assertFalse(new AccessScope(e,UUID.randomUUID(),null).permits(e,k)); }
}
