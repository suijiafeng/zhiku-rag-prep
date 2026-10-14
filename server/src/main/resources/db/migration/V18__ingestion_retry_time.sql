-- 失败后到 next_attempt_at 才重试，避免一次轮询连续耗尽所有次数。
ALTER TABLE ingestion_job ADD COLUMN next_attempt_at timestamptz;
