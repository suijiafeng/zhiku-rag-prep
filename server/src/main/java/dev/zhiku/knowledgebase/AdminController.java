package dev.zhiku.knowledgebase;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** IT_ADMIN views across the caller's enterprise. Users themselves live in the identity provider. */
@RestController
class AdminController {
  private final KnowledgeBaseService service;

  AdminController(KnowledgeBaseService service) {
    this.service = service;
  }

  @GetMapping("/api/admin/knowledge-bases")
  List<KnowledgeBaseRepository.AdminView> knowledgeBases() {
    return service.adminList();
  }
}
