-- PRD E01：评测样本补齐参考答案、必要事实、适用对象与查询时点；查询时点决定评测时按哪个时刻判断版本效力。
ALTER TABLE eval_case ADD COLUMN expected_answer text CHECK (char_length(expected_answer) <= 2000);
ALTER TABLE eval_case ADD COLUMN required_facts text[] NOT NULL DEFAULT '{}';
ALTER TABLE eval_case ADD COLUMN applies_to text CHECK (char_length(applies_to) <= 200);
ALTER TABLE eval_case ADD COLUMN query_at timestamptz;
