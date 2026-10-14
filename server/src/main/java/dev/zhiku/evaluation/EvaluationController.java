package dev.zhiku.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}")
class EvaluationController {
  record CreateCaseRequest(@NotBlank @Size(max = 2000) String question, @NotNull String label,
      @Size(max = 10) List<EvaluationService.EvidenceInput> evidence, @Size(max = 100) String source) {}

  record RunRequest(Integer k) {}

  private final EvaluationService service;

  EvaluationController(EvaluationService service) {
    this.service = service;
  }

  @GetMapping("/eval-cases")
  List<EvaluationService.CaseView> cases(@PathVariable UUID kbId) {
    return service.cases(kbId);
  }

  @PostMapping("/eval-cases")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, UUID> create(@PathVariable UUID kbId, @Valid @RequestBody CreateCaseRequest request) {
    return Map.of("id", service.createCase(kbId, request.question().strip(), request.label(), request.evidence(), request.source()));
  }

  @PostMapping("/eval-cases/{caseId}/approve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void approve(@PathVariable UUID kbId, @PathVariable UUID caseId) {
    service.approve(kbId, caseId);
  }

  @PostMapping("/eval-runs")
  EvaluationService.RunView run(@PathVariable UUID kbId, @RequestBody(required = false) RunRequest request) {
    return service.run(kbId, request == null ? null : request.k());
  }

  @GetMapping("/eval-runs/latest")
  ResponseEntity<EvaluationService.RunView> latest(@PathVariable UUID kbId) {
    var run = service.latest(kbId);
    return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
  }
}
