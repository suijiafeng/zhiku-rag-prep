package dev.zhiku.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DocumentViews {
  private DocumentViews() {}

  public record VersionView(UUID id, String versionLabel, Instant effectiveFrom, Instant effectiveUntil, boolean approved,
      boolean published, boolean withdrawn, boolean superseded, String indexStatus, String indexError) {}

  /** 仅对已删除文档存在：访问在 deletedAt 时停止；status 为内容清理状态。 */
  public record DeletionView(String status, Instant deletedAt, String error) {}

  public record DocumentView(UUID id, String title, Instant createdAt, List<VersionView> versions, DeletionView deletion) {}

  public record EligibilityView(UUID documentId, String title, VersionEligibility.Outcome outcome, UUID versionId) {}

  public record Created(UUID id) {}
}
