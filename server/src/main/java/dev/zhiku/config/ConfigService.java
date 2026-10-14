package dev.zhiku.config;

import static dev.zhiku.auth.KnowledgeBaseAction.DEBUG_AND_EVALUATE;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.chat.ChatSettings;
import dev.zhiku.evaluation.EvaluationService;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.RetrievalService;
import dev.zhiku.retrieval.RetrievalSettings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD C01：候选 → 验证 → 发布。验证只做边界检查和一次检索评测运行；人工质量评测不在其中，结果里明确标出。
 * Embedding 与切块参数不在此配置：改动它们要全量重建索引，必须另行确认。
 */
@Service
public class ConfigService {
  public record Input(int retrievalK, int contextChunks, String handoffChannel, String promptAddendum, String note) {}

  public record Validation(UUID evaluationRunId, int cases, boolean humanQualityChecked) {}

  public record View(UUID id, Integer version, String status, int revision, int retrievalK, int contextChunks, String handoffChannel,
      String promptAddendum, String note, UUID createdBy, Instant createdAt, Instant validatedAt, Validation validation, UUID publishedBy,
      Instant publishedAt, Integer rolledBackFrom) {}

  public record Defaults(int retrievalK, int contextChunks, String handoffChannel, int maxContextChunks) {}

  public record Overview(View published, View candidate, List<View> history, Defaults defaults) {}

  /** 某次请求实际使用的配置；version 0 表示部署默认值。 */
  public record Effective(int version, int retrievalK, int contextChunks, String handoffChannel, String promptAddendum) {}

  private final ConfigRepository repository;
  private final KnowledgeBaseAccess access;
  private final EvaluationService evaluation;
  private final RetrievalSettings retrieval;
  private final RetrievalService search;
  private final ChatSettings chat;
  private final AuditLog audit;

  public ConfigService(ConfigRepository repository, KnowledgeBaseAccess access, EvaluationService evaluation, RetrievalSettings retrieval,
      RetrievalService search, ChatSettings chat, AuditLog audit) {
    this.search = search;
    this.repository = repository;
    this.access = access;
    this.evaluation = evaluation;
    this.retrieval = retrieval;
    this.chat = chat;
    this.audit = audit;
  }

  /** 新请求在开始时读取一次；进行中的请求保持自己读到的版本。 */
  public Effective effective(UUID kbId) {
    return repository.published(kbId)
        .map(r -> new Effective(r.version(), r.retrievalK(), r.contextChunks(),
            blank(r.handoffChannel()) ? chat.handoffChannel() : r.handoffChannel(), r.promptAddendum()))
        .orElseGet(() -> new Effective(0, retrieval.defaultK(), chat.contextChunks(), chat.handoffChannel(), null));
  }

  /** 调试再运行用：读取某个已发布或被替代版本的参数；0 为部署默认值。不存在的版本返回 404。 */
  @Transactional(readOnly = true)
  public Effective version(UUID kbId, int version) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    if (version == 0) return new Effective(0, retrieval.defaultK(), chat.contextChunks(), chat.handoffChannel(), null);
    return repository.version(kbId, version)
        .map(r -> new Effective(r.version(), r.retrievalK(), r.contextChunks(), blank(r.handoffChannel()) ? chat.handoffChannel() : r.handoffChannel(),
            r.promptAddendum()))
        .orElseThrow(ApiException::notFound);
  }

  @Transactional(readOnly = true)
  public Overview overview(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    return new Overview(repository.published(kbId).map(ConfigService::view).orElse(null),
        repository.candidate(kbId, false).map(ConfigService::view).orElse(null),
        repository.history(kbId).stream().map(ConfigService::view).toList(),
        new Defaults(retrieval.defaultK(), chat.contextChunks(), chat.handoffChannel(), maxContextChunks()));
  }

  @Transactional
  public View saveCandidate(UUID kbId, Input raw, Integer expectedRevision) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    var in = normalize(raw);
    var existing = repository.candidate(kbId, true);
    UUID id;
    if (existing.isEmpty()) {
      if (expectedRevision != null) throw conflict("候选配置已不存在，请重新加载");
      id = repository.insertCandidate(kbId, in, scope.userId());
    } else {
      if (expectedRevision == null || repository.updateCandidate(existing.get().id(), expectedRevision, in) != 1) {
        throw conflict("候选配置已被他人修改，请重新加载后再确认");
      }
      id = existing.get().id();
    }
    audit.record(scope, "CONFIG_CANDIDATE_SAVE", "config:" + id, "OK", Map.of("retrievalK", in.retrievalK(), "contextChunks", in.contextChunks()));
    return view(repository.candidate(kbId, false).orElseThrow());
  }

  /**
   * 边界检查已在保存时完成。先用候选的召回 K 做一次真实检索，证明检索链路可用（没有评测用例时评测运行不会触碰检索）；
   * 再运行检索评测，作为发布依据记录。
   */
  @Transactional
  public View validate(UUID kbId, UUID candidateId, int expectedRevision) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    var candidate = current(kbId, candidateId, expectedRevision);
    search.searchAuthorized(scope, kbId, "配置验证", candidate.retrievalK(), Instant.now(), "evaluation", UUID.randomUUID(), 0);
    var run = evaluation.run(kbId, candidate.retrievalK());
    repository.markValidated(candidate.id(), candidate.revision(), run.id(), run.summary().cases());
    audit.record(scope, "CONFIG_VALIDATE", "config:" + candidate.id(), "OK", Map.of("evalRun", run.id().toString(), "cases", run.summary().cases()));
    return view(repository.candidate(kbId, false).orElseThrow());
  }

  @Transactional
  public View publish(UUID kbId, UUID candidateId, int expectedRevision) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    var candidate = current(kbId, candidateId, expectedRevision);
    if (candidate.validatedRevision() == null || candidate.validatedRevision() != candidate.revision()) {
      throw conflict("候选配置尚未验证，或验证后又被修改");
    }
    var version = repository.publish(kbId, candidate.id(), scope.userId());
    audit.record(scope, "CONFIG_PUBLISH", "config:" + candidate.id(), "OK", Map.of("version", version));
    return view(repository.published(kbId).orElseThrow());
  }

  /** 回滚是把旧版本的内容再发布为一个新版本，历史保持单向。 */
  @Transactional
  public View rollback(UUID kbId, int version) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    var from = repository.version(kbId, version).orElseThrow(ApiException::notFound);
    var next = repository.republish(kbId, from, scope.userId());
    audit.record(scope, "CONFIG_ROLLBACK", "config:" + from.id(), "OK", Map.of("from", version, "version", next));
    return view(repository.published(kbId).orElseThrow());
  }

  /** 乐观锁同时绑定候选身份与修订号：候选被发布后新建的候选从修订 1 重新计数，旧页面的操作不能落到它身上。 */
  private ConfigRepository.Row current(UUID kbId, UUID candidateId, int expectedRevision) {
    var candidate = repository.candidate(kbId, true).orElseThrow(() -> conflict("没有候选配置"));
    if (!candidate.id().equals(candidateId)) throw conflict("候选配置已更换（原候选已发布或被重建），请重新加载后再确认");
    if (candidate.revision() != expectedRevision) throw conflict("候选配置已被他人修改，请重新加载后再确认");
    return candidate;
  }

  /** 配置允许的入模块数上限：块数 × 单块上限不能超过总预算，否则组合必然超出模型上下文。 */
  private int maxContextChunks() {
    return Math.max(1, Math.min(20, chat.maxContextChars() / chat.maxChunkChars()));
  }

  private Input normalize(Input in) {
    if (in.retrievalK() < 1 || in.retrievalK() > retrieval.maxK()) throw ApiException.unprocessable("retrievalK must be between 1 and " + retrieval.maxK());
    if (in.contextChunks() < 1 || in.contextChunks() > maxContextChunks() || in.contextChunks() > in.retrievalK()) {
      throw ApiException.unprocessable("contextChunks must be between 1 and min(" + maxContextChunks() + ", retrievalK); "
          + maxContextChunks() + " blocks of max-chunk-chars fill the context budget");
    }
    var addendum = trimToNull(in.promptAddendum());
    if (addendum != null && addendum.length() > 1000) throw ApiException.unprocessable("promptAddendum must be at most 1000 characters");
    var channel = trimToNull(in.handoffChannel());
    if (channel != null && channel.length() > 200) throw ApiException.unprocessable("handoffChannel must be at most 200 characters");
    var note = trimToNull(in.note());
    if (note != null && note.length() > 500) throw ApiException.unprocessable("note must be at most 500 characters");
    return new Input(in.retrievalK(), in.contextChunks(), channel, addendum, note);
  }

  private static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "CONFIG_CONFLICT", message);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String trimToNull(String s) {
    return blank(s) ? null : s.strip();
  }

  static View view(ConfigRepository.Row r) {
    var validation = r.validationRunId() == null ? null : new Validation(r.validationRunId(), r.validationCases() == null ? 0 : r.validationCases(), false);
    return new View(r.id(), r.version(), r.status(), r.revision(), r.retrievalK(), r.contextChunks(), r.handoffChannel(), r.promptAddendum(), r.note(),
        r.createdBy(), r.createdAt(), r.validatedAt(), validation, r.publishedBy(), r.publishedAt(), r.rolledBackFrom());
  }
}
