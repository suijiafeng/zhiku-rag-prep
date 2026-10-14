package dev.zhiku.chat;

import dev.zhiku.knowledgebase.KnowledgeBaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FeedbackController {
  record RateRequest(@NotNull @Pattern(regexp = "up|down") String rating, String reason, @Size(max = 500) String comment) {}

  record StatusRequest(@NotNull String status, String rootCause, @Size(max = 1000) String resolution, UUID verificationCaseId) {}

  private final FeedbackService feedback;
  private final KnowledgeBaseService knowledgeBases;

  FeedbackController(FeedbackService feedback, KnowledgeBaseService knowledgeBases) {
    this.feedback = feedback;
    this.knowledgeBases = knowledgeBases;
  }

  @PutMapping("/api/messages/{messageId}/feedback")
  FeedbackRepository.FeedbackView rate(@PathVariable UUID messageId, @Valid @RequestBody RateRequest request) {
    return feedback.rate(knowledgeBases.requireScope(), messageId, request.rating(), request.reason(), request.comment());
  }

  @GetMapping("/api/knowledge-bases/{kbId}/records")
  FeedbackService.Records records(@PathVariable UUID kbId, @RequestParam(defaultValue = "all") @Pattern(regexp = "all|up|down|none") String filter) {
    return feedback.records(kbId, filter);
  }

  @PatchMapping("/api/knowledge-bases/{kbId}/feedback/{messageId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void updateStatus(@PathVariable UUID kbId, @PathVariable UUID messageId, @Valid @RequestBody StatusRequest request) {
    feedback.updateStatus(kbId, messageId, request.status(), request.rootCause(), request.resolution(), request.verificationCaseId());
  }

  @GetMapping("/api/knowledge-bases/{kbId}/feedback/{messageId}/history")
  java.util.List<FeedbackRepository.HandlingView> handlingHistory(@PathVariable UUID kbId, @PathVariable UUID messageId) {
    return feedback.handlingHistory(kbId, messageId);
  }
}
