-- Uploaded content is immutable per version; a change means a new version.
CREATE TABLE content_blob (
  version_id  uuid PRIMARY KEY REFERENCES content_version (id) ON DELETE CASCADE,
  media_type  text        NOT NULL CHECK (media_type IN ('text/markdown', 'text/plain')),
  file_name   text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 200),
  byte_size   integer     NOT NULL CHECK (byte_size > 0),
  sha256      text        NOT NULL CHECK (char_length(sha256) = 64),
  content     bytea       NOT NULL,
  uploaded_by uuid        NOT NULL,
  uploaded_at timestamptz NOT NULL DEFAULT now()
);

-- Durable job with a lease: a crashed worker's job is reclaimed after lease_until.
CREATE TABLE ingestion_job (
  id          uuid PRIMARY KEY,
  version_id  uuid        NOT NULL UNIQUE REFERENCES content_version (id) ON DELETE CASCADE,
  status      text        NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
  attempts    integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  lease_owner text,
  lease_until timestamptz,
  last_error  text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ingestion_job_claim_idx ON ingestion_job (status, created_at);

-- Offsets are character offsets into the line-ending-normalized text (source spans for evaluation).
CREATE TABLE chunk (
  id           uuid PRIMARY KEY,
  version_id   uuid    NOT NULL REFERENCES content_version (id) ON DELETE CASCADE,
  seq          integer NOT NULL CHECK (seq >= 0),
  start_offset integer NOT NULL CHECK (start_offset >= 0),
  end_offset   integer NOT NULL,
  heading      text,
  text         text    NOT NULL,
  UNIQUE (version_id, seq),
  CHECK (end_offset > start_offset)
);
