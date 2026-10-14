-- PRD Q04: a client request id is accepted once per user. Only a digest of the question is kept, never the text;
-- the outcome is the linked message (complete / refused / cancelled / failed). No link yet means running or interrupted.
CREATE TABLE chat_request (
  enterprise_id     uuid        NOT NULL,
  user_id           uuid        NOT NULL,
  client_request_id uuid        NOT NULL,
  kb_id             uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  digest            text        NOT NULL CHECK (char_length(digest) = 64),
  message_id        uuid        REFERENCES message (id) ON DELETE SET NULL,
  accepted_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (enterprise_id, user_id, client_request_id)
);
