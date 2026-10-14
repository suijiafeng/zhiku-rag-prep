-- PRD F02: each handling change is kept; closing needs a basis (a fix verified by regression, or a reason not to fix).
ALTER TABLE feedback ADD COLUMN resolution text CHECK (char_length(resolution) <= 1000);
ALTER TABLE feedback ADD COLUMN verification_case_id uuid REFERENCES eval_case (id) ON DELETE SET NULL;

CREATE TABLE feedback_handling (
  id                   bigserial PRIMARY KEY,
  message_id           uuid        NOT NULL REFERENCES message (id) ON DELETE CASCADE,
  status               text        NOT NULL,
  root_cause           text,
  resolution           text,
  verification_case_id uuid,
  changed_by           uuid        NOT NULL,
  changed_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX feedback_handling_message_idx ON feedback_handling (message_id, id);
