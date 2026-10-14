-- C34: evidence whose source version is no longer eligible (withdrawn, deleted, expired) is its own outcome, neither a
-- retrieval miss nor a preparation failure, so scores do not depend on how far deletion cleanup has progressed.
ALTER TABLE eval_result ADD COLUMN source_unavailable boolean NOT NULL DEFAULT false;
