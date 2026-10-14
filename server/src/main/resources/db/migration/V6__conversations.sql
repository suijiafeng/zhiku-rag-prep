-- Only completed, validated answers are stored. Failed or cancelled drafts never reach history.
CREATE TABLE conversation (
  id            uuid PRIMARY KEY,
  enterprise_id uuid        NOT NULL,
  user_id       uuid        NOT NULL,
  kb_id         uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  title         text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_user_idx ON conversation (user_id, updated_at DESC);

CREATE TABLE message (
  id              uuid PRIMARY KEY,
  conversation_id uuid        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
  question        text        NOT NULL,
  answer          text        NOT NULL,
  model           text        NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX message_conversation_idx ON message (conversation_id, created_at);

-- Source dependencies of a derived answer. No foreign keys on purpose: a deleted or re-indexed source
-- must leave the dependency visible as missing so the answer is hidden on read.
CREATE TABLE message_source (
  message_id uuid    NOT NULL REFERENCES message (id) ON DELETE CASCADE,
  ordinal    integer NOT NULL CHECK (ordinal >= 1),
  version_id uuid    NOT NULL,
  chunk_id   uuid    NOT NULL,
  PRIMARY KEY (message_id, ordinal)
);
