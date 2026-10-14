-- PRD C01：每个知识库一份候选配置，验证后发布为新版本；旧版本保留可回滚。revision 用于乐观锁。
CREATE TABLE kb_config (
  id                 uuid PRIMARY KEY,
  kb_id              uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  status             text        NOT NULL CHECK (status IN ('candidate', 'published', 'superseded')),
  version            integer,
  revision           integer     NOT NULL DEFAULT 1,
  retrieval_k        integer     NOT NULL CHECK (retrieval_k BETWEEN 1 AND 30),
  context_chunks     integer     NOT NULL CHECK (context_chunks BETWEEN 1 AND 20 AND context_chunks <= retrieval_k),
  handoff_channel    text        CHECK (char_length(handoff_channel) <= 200),
  prompt_addendum    text        CHECK (char_length(prompt_addendum) <= 1000),
  note               text        CHECK (char_length(note) <= 500),
  created_by         uuid        NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  validated_at       timestamptz,
  validated_revision integer,
  validation_run_id  uuid        REFERENCES eval_run (id) ON DELETE SET NULL,
  validation_cases   integer,
  published_by       uuid,
  published_at       timestamptz,
  rolled_back_from   integer,
  CHECK ((status = 'candidate') = (version IS NULL)),
  UNIQUE (kb_id, version)
);
CREATE UNIQUE INDEX kb_config_one_candidate ON kb_config (kb_id) WHERE status = 'candidate';
CREATE UNIQUE INDEX kb_config_one_published ON kb_config (kb_id) WHERE status = 'published';

-- 问答与检索轨迹记录当时生效的配置版本；0 表示部署默认值。
ALTER TABLE retrieval_trace ADD COLUMN config_version integer NOT NULL DEFAULT 0;
