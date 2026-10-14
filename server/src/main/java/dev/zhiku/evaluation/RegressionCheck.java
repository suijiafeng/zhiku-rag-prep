package dev.zhiku.evaluation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Public evaluation query for other modules. "Passed" is a retrieval check only: every evidence span of an approved
 * case made from the given feedback message was retrieved in the knowledge base's latest run. It does not show that a
 * generated answer is correct.
 */
@Component
public class RegressionCheck {
  private final JdbcTemplate jdbc;

  RegressionCheck(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean passedLatestRun(UUID kbId, UUID caseId, UUID originMessageId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM eval_case c JOIN eval_result r ON r.case_id = c.id
          WHERE c.id = ? AND c.kb_id = ? AND c.origin_message_id = ? AND c.status = 'approved' AND NOT r.mapping_failed AND r.evidence_hit = r.evidence_total
            AND r.run_id = (SELECT id FROM eval_run WHERE kb_id = ? ORDER BY created_at DESC, id DESC LIMIT 1))
        """, Boolean.class, caseId, kbId, originMessageId, kbId));
  }
}
