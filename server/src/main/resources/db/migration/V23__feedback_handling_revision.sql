-- C58：处理修订号与反馈内容版本分离。每次处理状态变更递增；旧页面的处理操作带着过期修订号会被拒绝，
-- 而评测用例对 origin_feedback_version 的绑定不受处理操作影响。
ALTER TABLE feedback ADD COLUMN handling_revision integer NOT NULL DEFAULT 0;
