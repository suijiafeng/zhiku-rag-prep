-- PRD Q03 / C29: earlier turns put into a follow-up's prompt are dependencies of the new answer. Stored as a closure
-- (the turns used plus everything they depended on), so one level of checks covers every transitive dependency.
-- No foreign key on depends_on: a missing earlier turn must count as unavailable, not disappear silently.
CREATE TABLE message_context (
  message_id uuid NOT NULL REFERENCES message (id) ON DELETE CASCADE,
  depends_on uuid NOT NULL,
  PRIMARY KEY (message_id, depends_on)
);
