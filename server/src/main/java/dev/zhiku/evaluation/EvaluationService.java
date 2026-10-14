package dev.zhiku.evaluation;

import static dev.zhiku.auth.KnowledgeBaseAction.DEBUG_AND_EVALUATE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.audit.AuditLog;
import dev.zhiku.document.DocumentRepository;
import dev.zhiku.ingestion.TextChunker;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.Retrieval.Candidate;
import dev.zhiku.retrieval.RetrievalService;
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

  record EvidenceInput(UUID versionId, String quote) {}

  /** quote is null and available false once the source version is withdrawn or its document deleted; the span stays. */
  record EvidenceView(UUID versionId, String documentTitle, String versionLabel, int startOffset, int endOffset, String quote, boolean available) {}

  record CaseView(UUID id, String question, String label, String status, String source, Instant createdAt, List<EvidenceView> evidence,
      UUID originMessageId) {}

  record Ratio(int num, int den) {}

  record Summary(int cases, int evaluated, int prepFailed, int notApplicable, Ratio hit, Ratio recall, Double recallMacro, Long p95Ms) {}

  record ResultView(UUID caseId, String question, String label, boolean mappingFailed, boolean hit, int evidenceHit, int evidenceTotal, int latencyMs) {}

  record RunView(UUID id, int k, String model, Instant createdAt, Summary summary, List<ResultView> results) {}

  private record Span(UUID versionId, int start, int end) {}

  private final KnowledgeBaseAccess access;
  private final DocumentRepository documents;
  private final RetrievalService retrieval;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AuditLog audit;

  EvaluationService(KnowledgeBaseAccess access, DocumentRepository documents, RetrievalService retrieval, JdbcTemplate jdbc,
      ObjectMapper json, AuditLog audit) {
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
  private void insertEvidence(UUID kbId, UUID caseId, List<EvidenceInput> inputs) {
    for (int i = 0; i < inputs.size(); i++) {
      var e = inputs.get(i);
      var version = documents.findVersion(e.versionId()).filter(v -> v.kbId().equals(kbId))
          .orElseThrow(() -> ApiException.unprocessable("Evidence version is not in this knowledge base"));
      var text = normalizedContent(version.id());
      var quote = TextChunker.normalize(e.quote() == null ? "" : e.quote()).strip();
      int at = quote.length() < 2 ? -1 : text.indexOf(quote);
      if (at < 0) throw ApiException.unprocessable("Evidence quote was not found in the selected version");
      jdbc.update("INSERT INTO eval_evidence (case_id, ordinal, version_id, start_offset, end_offset, quote) VALUES (?, ?, ?, ?, ?, ?)",
          caseId, i + 1, version.id(), at, at + quote.length(), quote);
    }
  }

  /** Replaces the evidence of a candidate; approved cases are frozen so past runs stay comparable. */
  @Transactional
  void setEvidence(UUID kbId, UUID caseId, List<EvidenceInput> evidence) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    var status = jdbc.queryForList("SELECT status FROM eval_case WHERE id = ? AND kb_id = ?", String.class, caseId, kbId);
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
    for (var c : cases) {
      var caseId = UUID.fromString(c[0]);
      if (!"answerable".equals(c[2])) {
        notApplicable++;
        continue;
      }
      var spans = jdbc.query("SELECT version_id, start_offset, end_offset FROM eval_evidence WHERE case_id = ? ORDER BY ordinal",
          (rs, i) -> new Span(rs.getObject("version_id", UUID.class), rs.getInt("start_offset"), rs.getInt("end_offset")), caseId);
      var mapped = new ArrayList<Set<UUID>>();
      for (var s : spans) {
        mapped.add(new HashSet<>(jdbc.queryForList(
            "SELECT id FROM chunk WHERE version_id = ? AND start_offset < ? AND end_offset > ?", UUID.class, s.versionId(), s.end(), s.start())));
      }
      if (mapped.stream().anyMatch(Set::isEmpty)) {
        results.add(new ResultView(caseId, c[1], c[2], true, false, 0, spans.size(), 0));
        continue;
      }
      long started = System.nanoTime();
      var trace = retrieval.searchAuthorized(scope, kbId, c[1], k, Instant.now());
      int latency = (int) ((System.nanoTime() - started) / 1_000_000);
      model = trace.embeddingModel();
      var retrieved = new HashSet<UUID>();
      trace.candidates().stream().map(Candidate::chunkId).forEach(retrieved::add);
      int covered = (int) mapped.stream().filter(set -> set.stream().anyMatch(retrieved::contains)).count();
      results.add(new ResultView(caseId, c[1], c[2], false, covered > 0, covered, spans.size(), latency));
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
      jdbc.update("INSERT INTO eval_result (run_id, case_id, mapping_failed, hit, evidence_hit, evidence_total, latency_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
          runId, r.caseId(), r.mappingFailed(), r.hit(), r.evidenceHit(), r.evidenceTotal(), r.latencyMs());
    }
    audit.record(scope, "EVAL_RUN", "eval-run:" + runId, "OK", Map.of("k", k, "cases", cases.size()));
    return new RunView(runId, k, model, Instant.now(), summary, results);
  }

  static Summary summarize(int cases, int notApplicable, List<ResultView> results) {
    var evaluated = results.stream().filter(r -> !r.mappingFailed()).toList();
    int hits = (int) evaluated.stream().filter(ResultView::hit).count();
    int covered = evaluated.stream().mapToInt(ResultView::evidenceHit).sum();
    int spans = evaluated.stream().mapToInt(ResultView::evidenceTotal).sum();
    Double macro = evaluated.isEmpty() ? null
        : evaluated.stream().mapToDouble(r -> (double) r.evidenceHit() / r.evidenceTotal()).average().orElse(0);
    var latencies = evaluated.stream().mapToLong(ResultView::latencyMs).sorted().toArray();
    Long p95 = latencies.length == 0 ? null : latencies[(int) Math.ceil(0.95 * latencies.length) - 1];
    return new Summary(cases, evaluated.size(), results.size() - evaluated.size(), notApplicable, new Ratio(hits, evaluated.size()),
        new Ratio(covered, spans), macro, p95);
  }

  @Transactional(readOnly = true)
  RunView latest(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var runs = jdbc.query("SELECT id, k, model, created_at, summary FROM eval_run WHERE kb_id = ? ORDER BY created_at DESC LIMIT 1",
        (rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getInt("k"), rs.getString("model"), rs.getTimestamp("created_at").toInstant(), rs.getString("summary")}, kbId);
    if (runs.isEmpty()) return null;
    var r = runs.getFirst();
    var results = jdbc.query("""
        SELECT r.case_id, c.question, c.label, r.mapping_failed, r.hit, r.evidence_hit, r.evidence_total, r.latency_ms
        FROM eval_result r JOIN eval_case c ON c.id = r.case_id WHERE r.run_id = ? ORDER BY c.created_at, c.id
        """, (rs, i) -> new ResultView(rs.getObject("case_id", UUID.class), rs.getString("question"), rs.getString("label"),
            rs.getBoolean("mapping_failed"), rs.getBoolean("hit"), rs.getInt("evidence_hit"), rs.getInt("evidence_total"), rs.getInt("latency_ms")), r[0]);
    try {
      return new RunView((UUID) r[0], (Integer) r[1], (String) r[2], (Instant) r[3], json.readValue((String) r[4], Summary.class), results);
    } catch (JsonProcessingException e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Stored run summary is unreadable");
    }
  }
}
