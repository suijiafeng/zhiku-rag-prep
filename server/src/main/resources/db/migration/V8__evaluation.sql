-- Evaluation ground truth is stored as source spans in a content version, independent of chunk ids.
CREATE TABLE eval_case (
  id         uuid PRIMARY KEY,
  kb_id      uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  question   text        NOT NULL CHECK (char_length(question) BETWEEN 1 AND 2000),
  label      text        NOT NULL CHECK (label IN ('answerable', 'clarify', 'handoff')),
  status     text        NOT NULL DEFAULT 'candidate' CHECK (status IN ('candidate', 'approved')),
  source     text        NOT NULL DEFAULT 'manual',
  created_by uuid        NOT NULL,
  approved_by uuid,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX eval_case_kb_idx ON eval_case (kb_id, created_at);

CREATE TABLE eval_evidence (
  case_id      uuid    NOT NULL REFERENCES eval_case (id) ON DELETE CASCADE,
  ordinal      integer NOT NULL,
  version_id   uuid    NOT NULL REFERENCES content_version (id) ON DELETE CASCADE,
  start_offset integer NOT NULL CHECK (start_offset >= 0),
  end_offset   integer NOT NULL,
  quote        text    NOT NULL,
  PRIMARY KEY (case_id, ordinal),
  CHECK (end_offset > start_offset)
);

CREATE TABLE eval_run (
  id         uuid PRIMARY KEY,
  kb_id      uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  k          integer     NOT NULL,
  model      text,
  created_by uuid        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  summary    jsonb       NOT NULL
);

CREATE TABLE eval_result (
  run_id         uuid    NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,
  case_id        uuid    NOT NULL REFERENCES eval_case (id) ON DELETE CASCADE,
  mapping_failed boolean NOT NULL,
  hit            boolean NOT NULL,
  evidence_hit   integer NOT NULL,
  evidence_total integer NOT NULL,
  latency_ms     integer NOT NULL,
  PRIMARY KEY (run_id, case_id)
);
