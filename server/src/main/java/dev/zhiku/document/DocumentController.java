package dev.zhiku.document;

import dev.zhiku.document.DocumentViews.Created;
import dev.zhiku.document.DocumentViews.DocumentView;
import dev.zhiku.document.DocumentViews.EligibilityView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}")
class DocumentController {
  record CreateDocumentRequest(@NotBlank @Size(max = 200) String title) {}

  record CreateVersionRequest(@NotBlank @Size(max = 50) String versionLabel, @NotNull LocalDate effectiveFrom, LocalDate effectiveUntil) {}

  private final DocumentService service;

  DocumentController(DocumentService service) {
    this.service = service;
  }

  @GetMapping("/documents")
  List<DocumentView> list(@PathVariable UUID kbId) {
    return service.list(kbId);
  }

  @PostMapping("/documents")
  @ResponseStatus(HttpStatus.CREATED)
  Created create(@PathVariable UUID kbId, @Valid @RequestBody CreateDocumentRequest request) {
    return new Created(service.createDocument(kbId, request.title().strip()));
  }

  @PostMapping("/documents/{documentId}/versions")
  @ResponseStatus(HttpStatus.CREATED)
  Created createVersion(@PathVariable UUID kbId, @PathVariable UUID documentId, @Valid @RequestBody CreateVersionRequest request) {
    return new Created(service.createVersion(kbId, documentId, request.versionLabel().strip(), request.effectiveFrom(), request.effectiveUntil()));
  }

  @PostMapping("/documents/{documentId}/versions/{versionId}/approve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void approve(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId) {
    service.approve(kbId, documentId, versionId);
  }

  @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void publish(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId) {
    service.publish(kbId, documentId, versionId);
  }

  @PostMapping("/documents/{documentId}/versions/{versionId}/withdraw")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void withdraw(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId) {
    service.withdraw(kbId, documentId, versionId);
  }

  @GetMapping("/eligibility")
  List<EligibilityView> eligibility(@PathVariable UUID kbId, @RequestParam(required = false) Instant at) {
    return service.eligibility(kbId, at == null ? Instant.now() : at);
  }
}
