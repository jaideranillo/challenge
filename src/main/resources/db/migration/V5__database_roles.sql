-- ADR-007 §5.4: three roles, not two (Tech Lead review, 2026-09-21). Ownership and the
-- pipeline's RLS exemption are separated into two different principals, so the
-- exemption is one explicit BYPASSRLS grant, never a side effect of owning the schema.
-- No policy is created here - TASK-008-08 (V6) owns RLS and FORCE ROW LEVEL SECURITY.

-- challenge_owner: NOLOGIN group role. Owns the schema, the four tenant tables, their
-- indexes/sequences and the enum types. Never a runtime connection principal - Flyway
-- reaches DDL by membership (granted below), not by logging in as this role.
CREATE ROLE challenge_owner NOLOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS;

-- challenge_pipeline: LOGIN, BYPASSRLS explicitly. Not the owner and not a member of
-- challenge_owner, so it holds no DDL - only the DML the ingest, relay, worker and DLQ
-- consumer actually issue. No DELETE, no TRUNCATE (nothing in ADR-002 deletes a delivery).
-- BYPASSRLS chosen over the V6-documented fallback policy: both Testcontainers and the
-- compose dev Postgres run as superuser, which BYPASSRLS/ALTER ROLE ... BYPASSRLS requires.
CREATE ROLE challenge_pipeline LOGIN BYPASSRLS NOSUPERUSER NOCREATEROLE NOCREATEDB;

-- challenge_api: LOGIN, NOBYPASSRLS stated explicitly. Not the owner, not a member of
-- challenge_owner, not a superuser. SELECT only, on the four tenant tables - subject to
-- every RLS policy V6 creates.
CREATE ROLE challenge_api LOGIN NOBYPASSRLS NOSUPERUSER NOCREATEROLE NOCREATEDB;

COMMENT ON ROLE challenge_owner IS
  'ADR-007 §5.4: NOLOGIN owner of the schema, the four tenant tables, their sequences and enum types. Never a runtime connection principal - Flyway reaches DDL only by membership in this role, never by logging in as it.';
COMMENT ON ROLE challenge_pipeline IS
  'ADR-007 §5.4: the cross-tenant pipeline principal (ingest, relay, worker, DLQ consumer - the primary pool). Escapes RLS by an explicit BYPASSRLS grant, never by ownership. Must never gain DDL or challenge_owner membership.';
COMMENT ON ROLE challenge_api IS
  'ADR-007 §5.4: the client-API principal (the api pool). NOBYPASSRLS, SELECT only on the four tenant tables, subject to every RLS policy (V6). Must never gain BYPASSRLS or table ownership.';

-- No password literal: both LOGIN roles' passwords are Flyway placeholders, resolved from
-- environment-backed configuration (spring.flyway.placeholders.*, see application.yaml /
-- TASK-008-09) so nothing credential-shaped is committed to this file.
ALTER ROLE challenge_pipeline PASSWORD '${challengePipelinePassword}';
ALTER ROLE challenge_api PASSWORD '${challengeApiPassword}';

-- Ownership transfer: V1-V4 created the four tables under whatever principal Flyway ran
-- as. Move that ownership to challenge_owner, then grant the Flyway/migration user
-- membership in challenge_owner so V7 and every later migration keeps DDL.
ALTER SCHEMA public OWNER TO challenge_owner;
ALTER TABLE notification_events OWNER TO challenge_owner;
ALTER TABLE subscriptions OWNER TO challenge_owner;
ALTER TABLE deliveries OWNER TO challenge_owner;
ALTER TABLE delivery_attempts OWNER TO challenge_owner;
ALTER SEQUENCE delivery_attempts_id_seq OWNER TO challenge_owner;
ALTER TYPE delivery_status OWNER TO challenge_owner;
ALTER TYPE delivery_origin OWNER TO challenge_owner;
ALTER TYPE circuit_state OWNER TO challenge_owner;
ALTER TYPE verification_state OWNER TO challenge_owner;

GRANT challenge_owner TO CURRENT_USER;

-- challenge_pipeline: schema usage plus the DML the pipeline actually issues.
GRANT USAGE ON SCHEMA public TO challenge_pipeline;
GRANT SELECT, INSERT, UPDATE ON notification_events, subscriptions, deliveries, delivery_attempts
  TO challenge_pipeline;

-- challenge_api: schema usage plus SELECT only. The replay write goes through the
-- pipeline pool (FEAT-008 feature.md mechanism choice 2), so this role needs no INSERT,
-- UPDATE or DELETE anywhere.
GRANT USAGE ON SCHEMA public TO challenge_api;
GRANT SELECT ON notification_events, subscriptions, deliveries, delivery_attempts TO challenge_api;
