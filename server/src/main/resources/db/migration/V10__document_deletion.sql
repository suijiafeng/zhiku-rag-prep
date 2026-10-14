-- PRD D05: deletion first stops all reads (deleted_at), then a durable cleanup removes content, chunks, vectors and jobs.
-- The document and version rows stay as metadata so history and audit can show that a source was deleted.
ALTER TABLE document ADD COLUMN deleted_at timestamptz;
ALTER TABLE document ADD COLUMN deleted_by uuid;
ALTER TABLE document ADD COLUMN cleanup_status text CHECK (cleanup_status IN ('pending', 'running', 'done', 'failed'));
ALTER TABLE document ADD COLUMN cleanup_attempts integer NOT NULL DEFAULT 0 CHECK (cleanup_attempts >= 0);
ALTER TABLE document ADD COLUMN cleanup_lease_until timestamptz;
ALTER TABLE document ADD COLUMN cleanup_error text;
ALTER TABLE document ADD CONSTRAINT document_cleanup_only_when_deleted CHECK ((deleted_at IS NULL) = (cleanup_status IS NULL));
CREATE INDEX document_cleanup_idx ON document (cleanup_status) WHERE cleanup_status IN ('pending', 'running');
