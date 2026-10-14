-- Knowledge-base level ACL. Users and enterprises come from the identity provider; only grants live here.
CREATE TABLE knowledge_base (
  id            uuid PRIMARY KEY,
  enterprise_id uuid        NOT NULL,
  name          text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (enterprise_id, name)
);

CREATE TABLE kb_grant (
  kb_id      uuid        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
  user_id    uuid        NOT NULL,
  granted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (kb_id, user_id)
);
CREATE INDEX kb_grant_user_idx ON kb_grant (user_id);

-- Append-only audit trail. Detail holds non-sensitive metadata only.
CREATE TABLE audit_event (
  id            bigserial PRIMARY KEY,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  request_id    text        NOT NULL,
  enterprise_id uuid        NOT NULL,
  actor_id      uuid        NOT NULL,
  action        text        NOT NULL,
  target        text        NOT NULL,
  result        text        NOT NULL,
  detail        jsonb       NOT NULL DEFAULT '{}'::jsonb
);

CREATE FUNCTION audit_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_event is append-only';
END
$$;
CREATE TRIGGER audit_event_no_change BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();
