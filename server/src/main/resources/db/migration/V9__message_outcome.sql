-- PRD Q03: completed, refused, cancelled and failed turns are stored separately. Only completed turns keep answer
-- text and source dependencies; the others record the question and outcome but never a draft.
ALTER TABLE message ADD COLUMN status text NOT NULL DEFAULT 'complete'
  CHECK (status IN ('complete', 'refused', 'cancelled', 'failed'));
ALTER TABLE message ADD COLUMN error_code text;
ALTER TABLE message ALTER COLUMN answer DROP NOT NULL;
ALTER TABLE message ALTER COLUMN model DROP NOT NULL;
ALTER TABLE message ADD CONSTRAINT message_answer_only_when_complete CHECK ((status = 'complete') = (answer IS NOT NULL));
