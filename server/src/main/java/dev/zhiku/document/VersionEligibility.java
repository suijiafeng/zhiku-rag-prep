package dev.zhiku.document;

import dev.zhiku.auth.AccessScope;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minimal policy for one document (one confirmed subject). Applicability and supersession graph are not implemented. */
public final class VersionEligibility {
  private VersionEligibility() {}

  public enum Outcome { ELIGIBLE, NO_EVIDENCE, CONFLICT, INDEX_NOT_READY }

  public record Version(UUID id, UUID enterpriseId, UUID knowledgeBaseId, boolean approved, boolean published,
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

  /** The chosen version is present only for ELIGIBLE; never a fallback to an older version. */
  public record Selection(Outcome outcome, Version version) {}

  public static Selection select(AccessScope scope, List<Version> candidates, Instant at) {
    Objects.requireNonNull(at);
    if (scope == null || candidates == null) return new Selection(Outcome.NO_EVIDENCE, null);
    var eligible = candidates.stream().filter(Objects::nonNull)
        .filter(v -> scope.permits(v.enterpriseId(), v.knowledgeBaseId())).filter(v -> v.effectiveAt(at)).toList();
    if (eligible.size() > 1) return new Selection(Outcome.CONFLICT, null);
    if (eligible.isEmpty()) return new Selection(Outcome.NO_EVIDENCE, null);
    var only = eligible.getFirst();
    return only.indexReady() ? new Selection(Outcome.ELIGIBLE, only) : new Selection(Outcome.INDEX_NOT_READY, null);
  }

  public static Outcome evaluate(AccessScope scope, List<Version> candidates, Instant at) {
    return select(scope, candidates, at).outcome();
  }
}
