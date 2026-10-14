package dev.zhiku.evaluation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 供其他模块使用的评测查询。"通过"只是检索检查，不能说明生成的回答正确。 */
@Component
public class RegressionCheck {
  private final JdbcTemplate jdbc;

  RegressionCheck(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 用例须由该反馈的当前版本生成，且通过的是本库最近一次运行；运行之后证据来源被撤回、删除、
   * 未就绪或重建过，旧的通过结果都不再算数，需要重新评测。
   */
  public boolean passedLatestRun(UUID kbId, UUID caseId, UUID originMessageId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM eval_case c JOIN eval_result r ON r.case_id = c.id JOIN eval_run run ON run.id = r.run_id
          WHERE c.id = ? AND c.kb_id = ? AND c.origin_message_id = ? AND c.status = 'approved'
            AND c.origin_feedback_version = (SELECT f.version FROM feedback f WHERE f.message_id = c.origin_message_id)
            AND NOT r.mapping_failed AND NOT r.source_unavailable AND r.evidence_hit = r.evidence_total
            AND run.id = (SELECT id FROM eval_run WHERE kb_id = ? ORDER BY created_at DESC, id DESC LIMIT 1)
            AND NOT EXISTS (
              SELECT 1 FROM eval_evidence e JOIN content_version v ON v.id = e.version_id JOIN document d ON d.id = v.document_id
              WHERE e.case_id = c.id AND (v.withdrawn_at IS NOT NULL OR d.deleted_at IS NOT NULL OR v.index_status <> 'ready'
                OR EXISTS (SELECT 1 FROM ingestion_job j WHERE j.version_id = v.id AND j.updated_at > run.created_at))))
        """, Boolean.class, caseId, kbId, originMessageId, kbId));
  }
}
