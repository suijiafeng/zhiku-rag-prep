package dev.zhiku.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 授权检索的结果类型。阶段状态遵循 PRD 第 11 章：success、failed、skipped、cancelled。 */
public final class Retrieval {
  private Retrieval() {}

  public record Candidate(UUID chunkId, UUID documentId, String title, UUID versionId, String versionLabel, int seq,
      String heading, int startOffset, int endOffset, String text, double score, int rank) {}

  public record Stage(String key, String name, String status, Instant startedAt, Instant endedAt, Long elapsedMs, String summary) {

    public static Stage skipped(String key, String name, String summary) {
      return new Stage(key, name, "skipped", null, null, null, summary);
    }

    public static Stage timed(String key, String name, String status, Instant startedAt, String summary) {
      var ended = Instant.now();
      return new Stage(key, name, status, startedAt, ended, java.time.Duration.between(startedAt, ended).toMillis(), summary);
    }
  }

  public record Result(UUID traceId, String question, Instant at, String embeddingModel, Integer dimension,
      List<Stage> stages, List<Candidate> candidates) {}

  public record TraceCandidate(int rank, UUID chunkId, UUID versionId, String documentTitle, double score) {}

  /** 存档的轨迹：只有元数据，没有问题和片段正文。 */
  public record TraceView(UUID traceId, String origin, Instant at, int k, String embeddingModel, Integer dimension, UUID messageId,
      Instant createdAt, List<Stage> stages, List<TraceCandidate> candidates, int configVersion) {}
}
