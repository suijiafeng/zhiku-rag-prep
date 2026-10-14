package dev.zhiku.retrieval;

import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.config.ConfigService;
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
  /** configVersion：按该已发布/被替代配置版本的召回 K 再运行（k 可覆盖）；0 为部署默认值；缺省时只按 k 或默认 K。 */
  record DebugRequest(@NotBlank @Size(max = 2000) String query, Integer k, Instant at, Integer configVersion) {}

  private final RetrievalService service;

  private final ConfigService config;

  RetrievalController(RetrievalService service, ConfigService config) {
    this.service = service;
    this.config = config;
  }

  /** 面向拥有调试权限的运营人员的检索 trace。候选限定在调用方的授权范围内。 */
  @GetMapping("/api/knowledge-bases/{kbId}/traces/{traceId}")
  Retrieval.TraceView trace(@PathVariable UUID kbId, @PathVariable UUID traceId) {
    return service.trace(kbId, traceId);
  }

  @PostMapping("/api/knowledge-bases/{kbId}/retrieval-debug")
  Retrieval.Result debug(@PathVariable UUID kbId, @Valid @RequestBody DebugRequest request) {
    var at = request.at() == null ? Instant.now() : request.at();
    if (request.configVersion() == null) {
      return service.search(kbId, request.query().strip(), request.k(), at, KnowledgeBaseAction.DEBUG_AND_EVALUATE);
    }
    // 再运行不改变配置，只借用该版本的参数；得到新的 traceId，原轨迹保持不变。
    var effective = config.version(kbId, request.configVersion());
    return service.search(kbId, request.query().strip(), request.k() == null ? effective.retrievalK() : request.k(), at,
        KnowledgeBaseAction.DEBUG_AND_EVALUATE, effective.version());
  }
}
