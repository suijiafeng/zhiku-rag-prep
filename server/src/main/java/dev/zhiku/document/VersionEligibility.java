package dev.zhiku.document;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import dev.zhiku.auth.AccessScope;
/** Minimal fixture policy. Applicability and supersession graph are not implemented. */
public final class VersionEligibility {
 private VersionEligibility() {}
 public enum Outcome { ELIGIBLE, NO_EVIDENCE, CONFLICT, INDEX_NOT_READY }
 public record Version(UUID enterpriseId, UUID knowledgeBaseId, boolean approved, boolean published,
  boolean withdrawn, boolean superseded, boolean indexReady, Instant effectiveFrom, Instant effectiveUntil) {
  public Version {
   Objects.requireNonNull(effectiveFrom, "effectiveFrom");
   if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) throw new IllegalArgumentException("Invalid interval");
  }
  boolean effectiveAt(Instant at) {
   return approved && published && !withdrawn && !superseded && !at.isBefore(effectiveFrom)
    && (effectiveUntil == null || at.isBefore(effectiveUntil));
  }
 }
 /** Only pass candidates already grouped by one confirmed subject/applicability. */
 public static Outcome evaluate(AccessScope scope, List<Version> candidates, Instant at) {
  Objects.requireNonNull(at);
  if (scope == null || candidates == null) return Outcome.NO_EVIDENCE;
  var eligible = candidates.stream().filter(Objects::nonNull)
   .filter(v -> scope.permits(v.enterpriseId(), v.knowledgeBaseId())).filter(v -> v.effectiveAt(at)).toList();
  if (eligible.size() > 1) return Outcome.CONFLICT;
  if (eligible.isEmpty()) return Outcome.NO_EVIDENCE;
  return eligible.getFirst().indexReady() ? Outcome.ELIGIBLE : Outcome.INDEX_NOT_READY;
 }
}
