package dev.zhiku.knowledgebase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
class KnowledgeBaseController {
  record CreateKnowledgeBaseRequest(@NotBlank @Size(max = 100) String name) {}

  private final KnowledgeBaseService service;

  KnowledgeBaseController(KnowledgeBaseService service) {
    this.service = service;
  }

  @GetMapping
  List<KnowledgeBaseView> list() {
    return service.listGranted();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  KnowledgeBaseView create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
    return service.create(request.name().strip());
  }

  @PutMapping("/{kbId}/grants/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void grant(@PathVariable UUID kbId, @PathVariable UUID userId) {
    service.grant(kbId, userId);
  }

  @DeleteMapping("/{kbId}/grants/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID kbId, @PathVariable UUID userId) {
    service.revoke(kbId, userId);
  }
}
