-- 由反馈生成的用例记录当时的反馈版本，反馈修改后旧用例不能再作为关闭依据。
ALTER TABLE eval_case ADD COLUMN origin_feedback_version integer;
