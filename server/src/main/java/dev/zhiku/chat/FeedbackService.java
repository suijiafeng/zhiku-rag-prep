package dev.zhiku.chat;

import static dev.zhiku.auth.KnowledgeBaseAction.ASK_AND_CITE;
import static dev.zhiku.auth.KnowledgeBaseAction.DEBUG_AND_EVALUATE;
import static dev.zhiku.auth.KnowledgeBaseAction.READ_OTHERS_CONVERSATIONS;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.auth.AccessScope;
import dev.zhiku.evaluation.RegressionCheck;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD F01/F02：提问者对自己的回答评价；运营人员在已授权的知识库中处理反馈。
 * 他人的问题和评论正文仅在拥有 READ_OTHERS_CONVERSATIONS 时显示，目前没有角色拥有该权限。
 */
@Service
class FeedbackService {
  static final Set<String> REASONS = Set.of("WRONG", "MISSING", "BAD_CITATION", "SHOULD_REFUSE", "OUTDATED");
  static final Set<String> STATUSES = Set.of("open", "in_progress", "to_verify", "resolved", "wont_fix");
  /** PRD 第 11 章的根因分类；刻意不包含“模型问题”。 */
  static final List<String> ROOT_CAUSES = List.of("文档未就绪或版本错误", "解析/切块遗漏", "授权范围不符合预期", "标注证据未召回",
      "融合或重排误排", "上下文预算挤出", "生成没有遵循证据", "引用映射失败");

  record RecordItem(UUID messageId, Instant createdAt, boolean own, String question, int citationCount, String rating,
      String reason, String comment, String status, String rootCause, String resolution, UUID verificationCaseId, String outcome,
      Boolean sourceAvailable) {}

  record Stats(int total, int up, int down, int unrated, Map<String, Integer> reasons) {}

  record Records(List<RecordItem> items, Stats stats, boolean canReadOthers) {}

  private final FeedbackRepository repository;
  private final KnowledgeBaseAccess access;
  private final AuditLog audit;
  private final RegressionCheck regression;

  FeedbackService(FeedbackRepository repository, KnowledgeBaseAccess access, AuditLog audit, RegressionCheck regression) {
    this.repository = repository;
    this.access = access;
    this.audit = audit;
    this.regression = regression;
  }

  @Transactional
  FeedbackRepository.FeedbackView rate(AccessScope scope, UUID messageId, String rating, String reason, String comment) {
    var owner = repository.owner(messageId).orElseThrow(ApiException::forbidden);
    if (!owner.userId().equals(scope.userId()) || !owner.enterpriseId().equals(scope.enterpriseId())
        || !scope.permits(scope.enterpriseId(), owner.kbId(), ASK_AND_CITE)) {
      throw ApiException.forbidden();
    }
    if (!"complete".equals(owner.status())) throw ApiException.conflict("Only completed answers can be rated");
    if ("down".equals(rating) && (reason == null || !REASONS.contains(reason))) throw ApiException.unprocessable("A valid reason is required");
    return repository.upsert(messageId, scope.userId(), rating, "down".equals(rating) ? reason : null, blankToNull(comment));
  }

  @Transactional(readOnly = true)
  Records records(UUID kbId, String filter) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    boolean canReadOthers = scope.permits(scope.enterpriseId(), kbId, READ_OTHERS_CONVERSATIONS);
    var items = repository.records(kbId, filter).stream().map(r -> {
      boolean own = r.userId().equals(scope.userId());
      boolean visible = own || canReadOthers;
      // 评论可能引用回答；一旦其来源不可用，评论与回答一样被隐藏。
      boolean commentVisible = visible && !Boolean.FALSE.equals(r.sourceAvailable());
      return new RecordItem(r.messageId(), r.createdAt(), own, visible ? r.question() : null, r.citationCount(), r.rating(),
          r.reason(), commentVisible ? r.comment() : null, r.status(), r.rootCause(), r.resolution(), r.verificationCaseId(), r.outcome(),
          r.sourceAvailable());
    }).toList();
    var raw = repository.stats(kbId, Instant.now().minus(Duration.ofDays(7)));
    var reasons = new LinkedHashMap<String, Integer>();
    raw.forEach((k, v) -> {
      if (k.startsWith("reason:")) reasons.put(k.substring(7), v);
    });
    int up = raw.getOrDefault("up", 0);
    int down = raw.getOrDefault("down", 0);
    int unrated = raw.getOrDefault("unrated", 0);
    return new Records(items, new Stats(up + down + unrated, up, down, unrated, reasons), canReadOthers);
  }

  /**
   * 每次关闭都需要依据（PRD F02）："resolved" 需要根因、修复措施，以及一个由此反馈生成、已批准且在最近一次运行中
   * 通过的评测用例；"wont_fix" 需要说明原因。每次变更都追加到处理历史中。
   */
  @Transactional
  void updateStatus(UUID kbId, UUID messageId, String status, String rootCause, String resolution, UUID verificationCaseId) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    if (!STATUSES.contains(status)) throw ApiException.unprocessable("Unknown status");
    if (rootCause != null && !ROOT_CAUSES.contains(rootCause)) throw ApiException.unprocessable("Unknown root cause");
    var note = blankToNull(resolution);
    if (!repository.feedbackInKnowledgeBase(kbId, messageId)) throw ApiException.notFound();
    if ("wont_fix".equals(status) && note == null) throw ApiException.unprocessable("Closing without a fix needs a reason");
    if ("resolved".equals(status)) {
      if (rootCause == null || note == null || verificationCaseId == null) {
        throw ApiException.unprocessable("Resolving needs a root cause, the fix and a verification case");
      }
      if (!regression.passedLatestRun(kbId, verificationCaseId, messageId)) {
        throw ApiException.unprocessable("Verification case must be made from this feedback, approved and pass the latest evaluation run");
      }
    }
    repository.updateStatus(kbId, messageId, status, rootCause, note, verificationCaseId, scope.userId());
    var detail = new LinkedHashMap<String, Object>();
    detail.put("status", status);
    if (rootCause != null) detail.put("rootCause", rootCause);
    if (verificationCaseId != null) detail.put("verificationCase", verificationCaseId.toString());
    audit.record(scope, "FEEDBACK_STATUS", "message:" + messageId, "OK", detail);
  }

  @Transactional(readOnly = true)
  List<FeedbackRepository.HandlingView> handlingHistory(UUID kbId, UUID messageId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    return repository.handlingHistory(kbId, messageId);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
