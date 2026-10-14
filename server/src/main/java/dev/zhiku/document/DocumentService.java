package dev.zhiku.document;

import static dev.zhiku.auth.KnowledgeBaseAction.ASK_AND_CITE;
import static dev.zhiku.auth.KnowledgeBaseAction.MANAGE_DOCUMENTS;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.auth.AccessScope;
import dev.zhiku.document.DocumentRepository.VersionRow;
import dev.zhiku.document.DocumentViews.DocumentView;
import dev.zhiku.document.DocumentViews.EligibilityView;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
  private final KnowledgeBaseAccess access;
  private final DocumentRepository repository;
  private final BusinessSettings business;
  private final AuditLog audit;

  public DocumentService(KnowledgeBaseAccess access, DocumentRepository repository, BusinessSettings business, AuditLog audit) {
    this.access = access;
    this.repository = repository;
    this.business = business;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<DocumentView> list(UUID kbId) {
    access.require(kbId, ASK_AND_CITE);
    return repository.documentsIn(kbId);
  }

  @Transactional
  public UUID createDocument(UUID kbId, String title) {
    var scope = access.require(kbId, MANAGE_DOCUMENTS);
    var id = UUID.randomUUID();
    repository.insertDocument(id, kbId, title, scope.userId());
    audit.record(scope, "DOCUMENT_CREATE", "document:" + id, "OK", Map.of("kb", kbId.toString()));
    return id;
  }

  /** Creates version metadata. Content is attached separately and indexed by the ingestion job. */
  @Transactional
  public UUID createVersion(UUID kbId, UUID documentId, String label, LocalDate from, LocalDate until) {
    var scope = access.require(kbId, MANAGE_DOCUMENTS);
    requireDocument(kbId, documentId);
    var start = business.startOf(from);
    var end = until == null ? null : business.startOf(until);
    if (end != null && !end.isAfter(start)) throw ApiException.unprocessable("effectiveUntil must be after effectiveFrom");
    if (repository.labelTaken(documentId, label)) throw ApiException.conflict("Version label already exists");
    var id = UUID.randomUUID();
    repository.insertVersion(id, documentId, label, start, end, scope.userId());
    audit.record(scope, "VERSION_CREATE", "version:" + id, "OK", Map.of("document", documentId.toString(), "label", label));
    return id;
  }

  @Transactional
  public void approve(UUID kbId, UUID documentId, UUID versionId) {
    transition(kbId, documentId, versionId, "VERSION_APPROVE", (scope) -> repository.approve(versionId, scope.userId()));
  }

  @Transactional
  public void publish(UUID kbId, UUID documentId, UUID versionId) {
    transition(kbId, documentId, versionId, "VERSION_PUBLISH", (scope) -> repository.publish(versionId, scope.userId()));
  }

  @Transactional
  public void withdraw(UUID kbId, UUID documentId, UUID versionId) {
    transition(kbId, documentId, versionId, "VERSION_WITHDRAW", (scope) -> repository.withdraw(versionId));
  }

  /** Per-document outcome at a point in time; the caller's grants are re-checked inside the policy. */
  @Transactional(readOnly = true)
  public List<EligibilityView> eligibility(UUID kbId, Instant at) {
    var scope = access.require(kbId, ASK_AND_CITE);
    return evaluate(scope, repository.versionsIn(kbId), at);
  }

  /** Eligible version ids in a knowledge base, used by retrieval. */
  public List<UUID> eligibleVersionIds(AccessScope scope, UUID kbId, Instant at) {
    return evaluate(scope, repository.versionsIn(kbId), at).stream()
        .filter(e -> e.outcome() == VersionEligibility.Outcome.ELIGIBLE).map(EligibilityView::versionId).toList();
  }

  static List<EligibilityView> evaluate(AccessScope scope, List<VersionRow> rows, Instant at) {
    var byDocument = new LinkedHashMap<UUID, List<VersionRow>>();
    rows.forEach(r -> byDocument.computeIfAbsent(r.documentId(), k -> new ArrayList<>()).add(r));
    return byDocument.values().stream().map(versions -> {
      var selection = VersionEligibility.select(scope, versions.stream().map(VersionRow::toPolicy).toList(), at);
      var first = versions.getFirst();
      return new EligibilityView(first.documentId(), first.documentTitle(), selection.outcome(),
          selection.version() == null ? null : selection.version().id());
    }).toList();
  }

  private void requireDocument(UUID kbId, UUID documentId) {
    if (!repository.documentInKnowledgeBase(documentId, kbId)) throw ApiException.forbidden();
  }

  private void transition(UUID kbId, UUID documentId, UUID versionId, String action, java.util.function.ToIntFunction<AccessScope> change) {
    var scope = access.require(kbId, MANAGE_DOCUMENTS);
    if (repository.findVersion(kbId, documentId, versionId).isEmpty()) throw ApiException.forbidden();
    if (change.applyAsInt(scope) != 1) throw ApiException.conflict("Version state does not allow this transition");
    audit.record(scope, action, "version:" + versionId, "OK", Map.of());
  }
}
