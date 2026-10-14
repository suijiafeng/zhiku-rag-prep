package dev.zhiku.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Result types for authorized retrieval. Stage status follows PRD chapter 11: success, failed, skipped, cancelled. */
public final class Retrieval {
  private Retrieval() {}

  public record Candidate(UUID chunkId, UUID documentId, String title, UUID versionId, String versionLabel, int seq,
      String heading, int startOffset, int endOffset, String text, double score, int rank) {}

  public record Stage(String key, String name, String status, Long elapsedMs, String summary) {}

  public record Result(UUID traceId, String question, Instant at, String embeddingModel, Integer dimension,
      List<Stage> stages, List<Candidate> candidates) {}
}
