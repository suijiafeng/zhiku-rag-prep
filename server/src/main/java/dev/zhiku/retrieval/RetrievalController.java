package dev.zhiku.retrieval;

import dev.zhiku.auth.KnowledgeBaseAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RetrievalController {
  record DebugRequest(@NotBlank @Size(max = 2000) String query, Integer k, Instant at) {}

  private final RetrievalService service;

  RetrievalController(RetrievalService service) {
    this.service = service;
  }

  /** Retrieval trace for operators with debug rights. Candidates are limited to the caller's authorized scope. */
  @PostMapping("/api/knowledge-bases/{kbId}/retrieval-debug")
  Retrieval.Result debug(@PathVariable UUID kbId, @Valid @RequestBody DebugRequest request) {
    return service.search(kbId, request.query().strip(), request.k(), request.at() == null ? Instant.now() : request.at(),
        KnowledgeBaseAction.DEBUG_AND_EVALUATE);
  }
}
