-- 检索与生成轨迹只保存诊断元数据：不存问题、片段正文或向量。问答、调试与评测共用同一条检索链路。
CREATE TABLE retrieval_trace (
  id              uuid PRIMARY KEY,
  enterprise_id   uuid        NOT NULL,
  kb_id           uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  user_id         uuid        NOT NULL,
  origin          text        NOT NULL CHECK (origin IN ('chat', 'debug', 'evaluation')),
  at              timestamptz NOT NULL,
  k               integer     NOT NULL,
  embedding_model text,
  dimension       integer,
  message_id      uuid        REFERENCES message (id) ON DELETE SET NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX retrieval_trace_kb_idx ON retrieval_trace (kb_id, created_at DESC);

CREATE TABLE retrieval_trace_stage (
  trace_id   uuid        NOT NULL REFERENCES retrieval_trace (id) ON DELETE CASCADE,
  ordinal    integer     NOT NULL,
  key        text        NOT NULL,
  name       text        NOT NULL,
  status     text        NOT NULL CHECK (status IN ('success', 'failed', 'skipped', 'cancelled')),
  started_at timestamptz,
  ended_at   timestamptz,
  elapsed_ms bigint,
  summary    text,
  PRIMARY KEY (trace_id, ordinal)
);

CREATE TABLE retrieval_trace_candidate (
  trace_id   uuid             NOT NULL REFERENCES retrieval_trace (id) ON DELETE CASCADE,
  rank       integer          NOT NULL,
  chunk_id   uuid             NOT NULL,
  version_id uuid             NOT NULL,
  score      double precision NOT NULL,
  PRIMARY KEY (trace_id, rank)
);
