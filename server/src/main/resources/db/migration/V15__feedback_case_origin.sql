-- C30: a case made from a feedback keeps that message as its origin; only such a case can verify the feedback's fix.
ALTER TABLE eval_case ADD COLUMN origin_message_id uuid REFERENCES message (id) ON DELETE SET NULL;
-- C16: each handling change records the feedback version it was made for.
ALTER TABLE feedback_handling ADD COLUMN feedback_version integer;
