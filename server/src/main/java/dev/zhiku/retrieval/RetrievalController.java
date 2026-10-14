package dev.zhiku.retrieval;

import dev.zhiku.auth.KnowledgeBaseAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
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

  /** 面向拥有调试权限的运营人员的检索 trace。候选限定在调用方的授权范围内。 */
  @GetMapping("/api/knowledge-bases/{kbId}/traces/{traceId}")
  Retrieval.TraceView trace(@PathVariable UUID kbId, @PathVariable UUID traceId) {
    return service.trace(kbId, traceId);
  }

  @PostMapping("/api/knowledge-bases/{kbId}/retrieval-debug")
  Retrieval.Result debug(@PathVariable UUID kbId, @Valid @RequestBody DebugRequest request) {
    return service.search(kbId, request.query().strip(), request.k(), request.at() == null ? Instant.now() : request.at(),
        KnowledgeBaseAction.DEBUG_AND_EVALUATE);
  }
}
