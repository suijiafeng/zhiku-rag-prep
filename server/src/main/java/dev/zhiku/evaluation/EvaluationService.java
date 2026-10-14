package dev.zhiku.evaluation;

import static dev.zhiku.auth.KnowledgeBaseAction.DEBUG_AND_EVALUATE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.audit.AuditLog;
import dev.zhiku.document.DocumentRepository;
import dev.zhiku.document.DocumentService;
import dev.zhiku.ingestion.TextChunker;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.Retrieval.Candidate;
import dev.zhiku.retrieval.RetrievalService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD chapter 12, retrieval part: ground truth is a source span in a content version; each run maps spans onto the
 * current chunks. A span that maps to no chunk is a preparation failure, never a retrieval miss. No model is called.
 */
@Service
class EvaluationService {
  static final Set<String> LABELS = Set.of("answerable", "clarify", "handoff");

  /** occurrence (1-based) picks one of several identical passages; required when the quote occurs more than once. */
  record EvidenceInput(@NotNull UUID versionId, @NotBlank @Size(max = 2000) String quote, @Positive Integer occurrence) {}

  /** quote is null and available false once the source version is withdrawn or its document deleted; the span stays. */
  record EvidenceView(UUID versionId, String documentTitle, String versionLabel, int startOffset, int endOffset, String quote, boolean available) {}

  record CaseView(UUID id, String question, String label, String status, String source, Instant createdAt, List<EvidenceView> evidence,
      UUID originMessageId) {}

  record Ratio(int num, int den) {}

  record Summary(int cases, int evaluated, int prepFailed, int notApplicable, Ratio hit, Ratio recall, Double recallMacro, Long p95Ms,
      int sourceUnavailable) {}

  /** sourceUnavailable: an evidence version is not eligible now; such cases are reported but left out of the scores. */
  record ResultView(UUID caseId, String question, String label, boolean mappingFailed, boolean hit, int evidenceHit, int evidenceTotal, int latencyMs,
      boolean sourceUnavailable) {}

  record RunView(UUID id, int k, String model, Instant createdAt, Summary summary, List<ResultView> results) {}

  private record Span(UUID versionId, int start, int end) {}

  private final KnowledgeBaseAccess access;
  private final DocumentRepository documents;
  private final DocumentService eligibility;
  private final RetrievalService retrieval;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AuditLog audit;

  EvaluationService(KnowledgeBaseAccess access, DocumentRepository documents, DocumentService eligibility, RetrievalService retrieval,
      JdbcTemplate jdbc, ObjectMapper json, AuditLog audit) {
    this.eligibility = eligibility;
    this.access = access;
    this.documents = documents;
    this.retrieval = retrieval;
    this.jdbc = jdbc;
    this.json = json;
    this.audit = audit;
  }

  @Transactional
  UUID createCase(UUID kbId, String question, String label, List<EvidenceInput> evidence, String source, UUID originMessageId) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    if (!LABELS.contains(label)) throw ApiException.unprocessable("Unknown label");
    requireEntries(evidence);
    if (originMessageId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE m.id = ? AND c.kb_id = ?)",
        Boolean.class, originMessageId, kbId))) {
      throw ApiException.unprocessable("Origin message is not in this knowledge base");
    }
    var inputs = evidence == null ? List.<EvidenceInput>of() : evidence;
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO eval_case (id, kb_id, question, label, source, created_by, origin_message_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, kbId, question, label, source == null ? "manual" : source, scope.userId(), originMessageId);
    insertEvidence(kbId, id, inputs);
    audit.record(scope, "EVAL_CASE_CREATE", "eval-case:" + id, "OK", Map.of("label", label, "evidence", inputs.size()));
    return id;
  }

  /** Locates each quote in its version's normalized text and stores the character span. */
  /** Element constraints are validated per entry (@Valid); a null entry is invalid input, checked before any write. */
  static void requireEntries(List<EvidenceInput> inputs) {
    if (inputs != null && inputs.stream().anyMatch(java.util.Objects::isNull)) throw ApiException.unprocessable("Evidence entries must not be empty");
  }

  private void insertEvidence(UUID kbId, UUID caseId, List<EvidenceInput> inputs) {
    var seen = new HashSet<Span>();
    for (int i = 0; i < inputs.size(); i++) {
      var e = inputs.get(i);
      var version = documents.findVersion(e.versionId()).filter(v -> v.kbId().equals(kbId))
          .orElseThrow(() -> ApiException.unprocessable("Evidence version is not in this knowledge base"));
      var text = normalizedContent(version.id());
      var quote = TextChunker.normalize(e.quote() == null ? "" : e.quote()).strip();
      var at = locate(text, quote, e.occurrence());
      if (!seen.add(new Span(version.id(), at, at + quote.length()))) throw ApiException.unprocessable("The same evidence span is listed twice");
      jdbc.update("INSERT INTO eval_evidence (case_id, ordinal, version_id, start_offset, end_offset, quote) VALUES (?, ?, ?, ?, ?, ?)",
          caseId, i + 1, version.id(), at, at + quote.length(), quote);
    }
  }

  /** Start of the chosen occurrence; identical passages elsewhere are different ground truth, so ambiguity is refused. */
  static int locate(String text, String quote, Integer occurrence) {
    if (quote.length() < 2) throw ApiException.unprocessable("Evidence quote was not found in the selected version");
    var starts = new ArrayList<Integer>();
    for (int at = text.indexOf(quote); at >= 0; at = text.indexOf(quote, at + 1)) starts.add(at);
    if (starts.isEmpty()) throw ApiException.unprocessable("Evidence quote was not found in the selected version");
    if (occurrence == null) {
      if (starts.size() > 1) throw ApiException.unprocessable("Evidence quote occurs " + starts.size() + " times; choose which occurrence");
      return starts.getFirst();
    }
    if (occurrence > starts.size()) throw ApiException.unprocessable("Evidence quote occurs only " + starts.size() + " times");
    return starts.get(occurrence - 1);
  }


  /** Replaces the evidence of a candidate; approved cases are frozen so past runs stay comparable. */
  @Transactional
  void setEvidence(UUID kbId, UUID caseId, List<EvidenceInput> evidence) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    requireEntries(evidence);
    // Locks the case row: an approval in progress finishes first, and then the case is frozen (C22).
    var status = jdbc.queryForList("SELECT status FROM eval_case WHERE id = ? AND kb_id = ? FOR UPDATE", String.class, caseId, kbId);
    if (status.isEmpty()) throw ApiException.notFound();
    if (!"candidate".equals(status.getFirst())) throw ApiException.conflict("Approved cases cannot change evidence");
    jdbc.update("DELETE FROM eval_evidence WHERE case_id = ?", caseId);
    insertEvidence(kbId, caseId, evidence == null ? List.of() : evidence);
    audit.record(scope, "EVAL_CASE_EVIDENCE", "eval-case:" + caseId, "OK", Map.of("evidence", evidence == null ? 0 : evidence.size()));
  }

  private String normalizedContent(UUID versionId) {
    var bytes = jdbc.query("SELECT content FROM content_blob WHERE version_id = ?", (rs, i) -> rs.getBytes(1), versionId);
    if (bytes.isEmpty()) throw ApiException.unprocessable("Evidence version has no content");
    return TextChunker.normalize(new String(bytes.getFirst(), StandardCharsets.UTF_8).replaceFirst("^﻿", ""));
  }

  @Transactional
  void approve(UUID kbId, UUID caseId) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    // Same row lock as evidence edits, so the evidence checked here is the evidence that gets frozen.
    jdbc.queryForList("SELECT 1 FROM eval_case WHERE id = ? AND kb_id = ? FOR UPDATE", Integer.class, caseId, kbId);
    var needsEvidence = jdbc.queryForList(
        "SELECT 1 FROM eval_case c WHERE c.id = ? AND c.kb_id = ? AND c.label = 'answerable' AND NOT EXISTS (SELECT 1 FROM eval_evidence e WHERE e.case_id = c.id)",
        Integer.class, caseId, kbId);
    if (!needsEvidence.isEmpty()) throw ApiException.unprocessable("Answerable cases need evidence before approval");
    if (jdbc.update("UPDATE eval_case SET status = 'approved', approved_by = ? WHERE id = ? AND kb_id = ? AND status = 'candidate'",
        scope.userId(), caseId, kbId) != 1) {
      throw ApiException.conflict("Case is not a candidate in this knowledge base");
    }
    audit.record(scope, "EVAL_CASE_APPROVE", "eval-case:" + caseId, "OK", Map.of());
  }

  @Transactional(readOnly = true)
  List<CaseView> cases(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var evidence = new LinkedHashMap<UUID, List<EvidenceView>>();
    jdbc.query("""
        SELECT e.case_id, e.version_id, d.title, v.version_label, e.start_offset, e.end_offset, e.quote,
               d.deleted_at IS NULL AND v.withdrawn_at IS NULL AS available
        FROM eval_evidence e JOIN eval_case c ON c.id = e.case_id JOIN content_version v ON v.id = e.version_id JOIN document d ON d.id = v.document_id
        WHERE c.kb_id = ? ORDER BY e.case_id, e.ordinal
        """, rs -> {
          evidence.computeIfAbsent(rs.getObject("case_id", UUID.class), k -> new ArrayList<>()).add(new EvidenceView(
              rs.getObject("version_id", UUID.class), rs.getString("title"), rs.getString("version_label"),
              rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getBoolean("available") ? rs.getString("quote") : null,
              rs.getBoolean("available")));
        }, kbId);
    return jdbc.query("SELECT id, question, label, status, source, created_at, origin_message_id FROM eval_case WHERE kb_id = ? ORDER BY created_at DESC, id",
        (rs, i) -> {
          var id = rs.getObject("id", UUID.class);
          return new CaseView(id, rs.getString("question"), rs.getString("label"), rs.getString("status"), rs.getString("source"),
              rs.getTimestamp("created_at").toInstant(), evidence.getOrDefault(id, List.of()), rs.getObject("origin_message_id", UUID.class));
        }, kbId);
  }

  @Transactional
  RunView run(UUID kbId, Integer requestedK) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    int k = requestedK == null ? 5 : requestedK;
    if (k < 1 || k > 30) throw ApiException.unprocessable("k must be between 1 and 30");
    var cases = jdbc.query("SELECT id, question, label FROM eval_case WHERE kb_id = ? AND status = 'approved' ORDER BY created_at, id",
        (rs, i) -> new String[] {rs.getString("id"), rs.getString("question"), rs.getString("label")}, kbId);
    var results = new ArrayList<ResultView>();
    String model = null;
    int notApplicable = 0;
    var eligible = new HashSet<>(eligibility.eligibleVersionIds(scope, kbId, Instant.now()));
    for (var c : cases) {
      var caseId = UUID.fromString(c[0]);
      if (!"answerable".equals(c[2])) {
        notApplicable++;
        continue;
      }
      var spans = jdbc.query("SELECT version_id, start_offset, end_offset FROM eval_evidence WHERE case_id = ? ORDER BY ordinal",
          (rs, i) -> new Span(rs.getObject("version_id", UUID.class), rs.getInt("start_offset"), rs.getInt("end_offset")), caseId);
      if (!spans.stream().allMatch(sp -> eligible.contains(sp.versionId()))) {
        results.add(new ResultView(caseId, c[1], c[2], false, false, 0, spans.size(), 0, true));
        continue;
      }
      var mapped = new ArrayList<Map<UUID, int[]>>();
      for (var sp : spans) {
        var overlapping = new LinkedHashMap<UUID, int[]>();
        jdbc.query("SELECT id, start_offset, end_offset FROM chunk WHERE version_id = ? AND start_offset < ? AND end_offset > ?",
            rs -> {
              overlapping.put(rs.getObject("id", UUID.class), new int[] {rs.getInt("start_offset"), rs.getInt("end_offset")});
            }, sp.versionId(), sp.end(), sp.start());
        mapped.add(overlapping);
      }
      if (mapped.stream().anyMatch(Map::isEmpty)) {
        results.add(new ResultView(caseId, c[1], c[2], true, false, 0, spans.size(), 0, false));
        continue;
      }
      long started = System.nanoTime();
      var trace = retrieval.searchAuthorized(scope, kbId, c[1], k, Instant.now());
      int latency = (int) ((System.nanoTime() - started) / 1_000_000);
      model = trace.embeddingModel();
      var retrieved = new HashSet<UUID>();
      trace.candidates().stream().map(Candidate::chunkId).forEach(retrieved::add);
      // A span is retrieved only when the retrieved chunks together cover all of it, not when one character overlaps.
      int covered = 0;
      for (int i = 0; i < spans.size(); i++) {
        var pieces = mapped.get(i).entrySet().stream().filter(en -> retrieved.contains(en.getKey())).map(Map.Entry::getValue).toList();
        if (covers(spans.get(i).start(), spans.get(i).end(), pieces)) covered++;
      }
      results.add(new ResultView(caseId, c[1], c[2], false, covered > 0, covered, spans.size(), latency, false));
    }
    var summary = summarize(cases.size(), notApplicable, results);
    var runId = UUID.randomUUID();
    try {
      jdbc.update("INSERT INTO eval_run (id, kb_id, k, model, created_by, summary) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))",
          runId, kbId, k, model, scope.userId(), json.writeValueAsString(summary));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    for (var r : results) {
      jdbc.update("INSERT INTO eval_result (run_id, case_id, mapping_failed, hit, evidence_hit, evidence_total, latency_ms, source_unavailable) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", runId, r.caseId(), r.mappingFailed(), r.hit(), r.evidenceHit(), r.evidenceTotal(), r.latencyMs(),
          r.sourceUnavailable());
    }
    audit.record(scope, "EVAL_RUN", "eval-run:" + runId, "OK", Map.of("k", k, "cases", cases.size()));
    return new RunView(runId, k, model, Instant.now(), summary, results);
  }

  /** Whether the intervals [from, to) together cover [start, end) without a gap. */
  static boolean covers(int start, int end, List<int[]> intervals) {
    var sorted = intervals.stream().sorted(java.util.Comparator.comparingInt(iv -> iv[0])).toList();
    int reached = start;
    for (var iv : sorted) {
      if (iv[0] > reached) break;
      reached = Math.max(reached, iv[1]);
      if (reached >= end) return true;
    }
    return false;
  }

  static Summary summarize(int cases, int notApplicable, List<ResultView> results) {
    var evaluated = results.stream().filter(r -> !r.mappingFailed() && !r.sourceUnavailable()).toList();
    int unavailable = (int) results.stream().filter(ResultView::sourceUnavailable).count();
    int hits = (int) evaluated.stream().filter(ResultView::hit).count();
    int covered = evaluated.stream().mapToInt(ResultView::evidenceHit).sum();
    int spans = evaluated.stream().mapToInt(ResultView::evidenceTotal).sum();
    Double macro = evaluated.isEmpty() ? null
        : evaluated.stream().mapToDouble(r -> (double) r.evidenceHit() / r.evidenceTotal()).average().orElse(0);
    var latencies = evaluated.stream().mapToLong(ResultView::latencyMs).sorted().toArray();
    Long p95 = latencies.length == 0 ? null : latencies[(int) Math.ceil(0.95 * latencies.length) - 1];
    return new Summary(cases, evaluated.size(), results.size() - evaluated.size() - unavailable, notApplicable, new Ratio(hits, evaluated.size()),
        new Ratio(covered, spans), macro, p95, unavailable);
  }

  @Transactional(readOnly = true)
  RunView latest(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var runs = jdbc.query("SELECT id, k, model, created_at, summary FROM eval_run WHERE kb_id = ? ORDER BY created_at DESC LIMIT 1",
        (rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getInt("k"), rs.getString("model"), rs.getTimestamp("created_at").toInstant(), rs.getString("summary")}, kbId);
    if (runs.isEmpty()) return null;
    var r = runs.getFirst();
    var results = jdbc.query("""
        SELECT r.case_id, c.question, c.label, r.mapping_failed, r.hit, r.evidence_hit, r.evidence_total, r.latency_ms, r.source_unavailable
        FROM eval_result r JOIN eval_case c ON c.id = r.case_id WHERE r.run_id = ? ORDER BY c.created_at, c.id
        """, (rs, i) -> new ResultView(rs.getObject("case_id", UUID.class), rs.getString("question"), rs.getString("label"),
            rs.getBoolean("mapping_failed"), rs.getBoolean("hit"), rs.getInt("evidence_hit"), rs.getInt("evidence_total"), rs.getInt("latency_ms"),
            rs.getBoolean("source_unavailable")), r[0]);
    try {
      return new RunView((UUID) r[0], (Integer) r[1], (String) r[2], (Instant) r[3], json.readValue((String) r[4], Summary.class), results);
    } catch (JsonProcessingException e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Stored run summary is unreadable");
    }
  }
}
