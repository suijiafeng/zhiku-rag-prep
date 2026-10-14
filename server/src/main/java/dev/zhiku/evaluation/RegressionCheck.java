package dev.zhiku.evaluation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 供其他模块使用的公开评测查询。"Passed" 仅是检索检查：由给定反馈消息生成的已批准用例的
 * 每个证据片段都在该知识库最近一次运行中被检索到。它不能说明
 * 生成的回答正确。
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
