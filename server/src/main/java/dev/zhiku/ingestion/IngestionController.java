package dev.zhiku.ingestion;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/documents/{documentId}/versions/{versionId}")
class IngestionController {
  private final IngestionService service;

  IngestionController(IngestionService service) {
    this.service = service;
  }

  @PutMapping(path = "/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, UUID> upload(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId,
      @RequestPart("file") MultipartFile file) throws IOException {
    return Map.of("jobId", service.upload(kbId, documentId, versionId, file));
  }

  @PostMapping("/reindex")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void reindex(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId) {
    service.reindex(kbId, documentId, versionId);
  }
}
