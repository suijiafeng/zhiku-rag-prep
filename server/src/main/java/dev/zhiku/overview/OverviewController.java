package dev.zhiku.overview;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OverviewController {
  private final OverviewService service;

  OverviewController(OverviewService service) {
    this.service = service;
  }

  @GetMapping("/api/knowledge-bases/{kbId}/overview")
  OverviewService.Overview overview(@PathVariable UUID kbId) {
    return service.overview(kbId);
  }
}
