-- LOCAL development seed with neutral fictional identifiers. Never run against production.
-- Matches the example commands in README.md.
INSERT INTO knowledge_base (id, enterprise_id, name)
VALUES ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000e1', '员工公共制度库（示例）')
ON CONFLICT DO NOTHING;
INSERT INTO kb_grant (kb_id, user_id)
VALUES ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000a1'),
       ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000a2')
ON CONFLICT DO NOTHING;
