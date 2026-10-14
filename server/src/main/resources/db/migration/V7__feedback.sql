-- Feedback on a stored answer by the person who asked it. Changes keep a history.
CREATE TABLE feedback (
  message_id uuid PRIMARY KEY REFERENCES message (id) ON DELETE CASCADE,
  user_id    uuid        NOT NULL,
  rating     text        NOT NULL CHECK (rating IN ('up', 'down')),
  reason     text CHECK (reason IN ('WRONG', 'MISSING', 'BAD_CITATION', 'SHOULD_REFUSE', 'OUTDATED')),
  comment    text CHECK (char_length(comment) <= 500),
  version    integer     NOT NULL DEFAULT 1,
  status     text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'to_verify', 'resolved', 'wont_fix')),
  root_cause text,
  handled_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (rating = 'up' OR reason IS NOT NULL)
);

CREATE TABLE feedback_history (
  id         bigserial PRIMARY KEY,
  message_id uuid        NOT NULL REFERENCES message (id) ON DELETE CASCADE,
  version    integer     NOT NULL,
  rating     text        NOT NULL,
  reason     text,
  comment    text,
  changed_at timestamptz NOT NULL DEFAULT now()
);
