-- C24: each cleanup claim gets its own token; completion and failure updates must present it, so a worker whose
-- lease expired cannot change the state of the worker that claimed the document after it.
ALTER TABLE document ADD COLUMN cleanup_token uuid;
