package dev.zhiku.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DocumentViews {
  private DocumentViews() {}

  public record VersionView(UUID id, String versionLabel, Instant effectiveFrom, Instant effectiveUntil, boolean approved,
      boolean published, boolean withdrawn, boolean superseded, String indexStatus, String indexError) {}

  public record DocumentView(UUID id, String title, Instant createdAt, List<VersionView> versions) {}

  public record EligibilityView(UUID documentId, String title, VersionEligibility.Outcome outcome, UUID versionId) {}

  public record Created(UUID id) {}
}
