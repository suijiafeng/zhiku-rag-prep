package dev.zhiku.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/config")
class ConfigController {
  record CandidateRequest(@NotNull Integer retrievalK, @NotNull Integer contextChunks, @Size(max = 200) String handoffChannel,
      @Size(max = 1000) String promptAddendum, @Size(max = 500) String note, Integer expectedRevision) {}

  /** 候选身份与修订号一起校验：候选被发布后新建的候选修订号从 1 重新计数，只比修订号会把旧操作用到新候选上。 */
  record RevisionRequest(@NotNull UUID candidateId, @NotNull Integer expectedRevision) {}

  private final ConfigService service;

  ConfigController(ConfigService service) {
    this.service = service;
  }

  @GetMapping
  ConfigService.Overview overview(@PathVariable UUID kbId) {
    return service.overview(kbId);
  }

  @PutMapping("/candidate")
  ConfigService.View save(@PathVariable UUID kbId, @Valid @RequestBody CandidateRequest request) {
    return service.saveCandidate(kbId, new ConfigService.Input(request.retrievalK(), request.contextChunks(), request.handoffChannel(),
        request.promptAddendum(), request.note()), request.expectedRevision());
  }

  @PostMapping("/candidate/validate")
  ConfigService.View validate(@PathVariable UUID kbId, @Valid @RequestBody RevisionRequest request) {
    return service.validate(kbId, request.candidateId(), request.expectedRevision());
  }

  @PostMapping("/candidate/publish")
  ConfigService.View publish(@PathVariable UUID kbId, @Valid @RequestBody RevisionRequest request) {
    return service.publish(kbId, request.candidateId(), request.expectedRevision());
  }

  @PostMapping("/versions/{version}/rollback")
  ConfigService.View rollback(@PathVariable UUID kbId, @PathVariable int version) {
    return service.rollback(kbId, version);
  }
}
