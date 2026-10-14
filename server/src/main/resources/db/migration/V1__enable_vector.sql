-- Planned Flyway migration. No JDBC/Flyway runner wired yet.
-- Explicitly applied only against a new local database; never automatically against production.
CREATE EXTENSION IF NOT EXISTS vector;
-- No dimension, vector column, ANN index or business tables until ADR-002 is accepted.
