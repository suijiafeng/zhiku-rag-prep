-- Business approval/publication/effectivity are stored separately from parsing/index state.
CREATE TABLE document (
  id         uuid PRIMARY KEY,
  kb_id      uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  title      text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
  created_by uuid        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX document_kb_idx ON document (kb_id);

CREATE TABLE content_version (
  id              uuid PRIMARY KEY,
  document_id     uuid        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
  version_label   text        NOT NULL CHECK (char_length(version_label) BETWEEN 1 AND 50),
  effective_from  timestamptz NOT NULL,
  effective_until timestamptz,
  approved_by     uuid,
  approved_at     timestamptz,
  published_by    uuid,
  published_at    timestamptz,
  withdrawn_at    timestamptz,
  superseded      boolean     NOT NULL DEFAULT false,
  index_status    text        NOT NULL DEFAULT 'pending' CHECK (index_status IN ('pending', 'processing', 'ready', 'failed')),
  index_error     text,
  created_by      uuid        NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, version_label),
  CHECK (effective_until IS NULL OR effective_until > effective_from),
  CHECK (published_at IS NULL OR approved_at IS NOT NULL)
);
CREATE INDEX content_version_document_idx ON content_version (document_id);
