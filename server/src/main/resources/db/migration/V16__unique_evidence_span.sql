-- C31: the same source span counted twice inflated recall. Remove existing repeats (keep the first) and forbid new ones.
DELETE FROM eval_evidence e USING eval_evidence first
WHERE e.case_id = first.case_id AND e.version_id = first.version_id AND e.start_offset = first.start_offset
  AND e.end_offset = first.end_offset AND e.ordinal > first.ordinal;
CREATE UNIQUE INDEX eval_evidence_span_uq ON eval_evidence (case_id, version_id, start_offset, end_offset);
