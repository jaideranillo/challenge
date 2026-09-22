-- ADR-007 §5.4: row level security on the four tenant tables under the three-role
-- model of V5. No roles or grants here (V5's concern), no application code.

-- FORCE ROW LEVEL SECURITY is set (reversed from an earlier draft, Tech Lead review
-- 2026-09-21). V5 separates ownership (challenge_owner, NOLOGIN) from the pipeline's
-- RLS exemption (challenge_pipeline, explicit BYPASSRLS) - a BYPASSRLS role bypasses
-- RLS whether or not FORCE is set, so FORCE costs the pipeline nothing. Without FORCE,
-- the Flyway/migration user's membership in challenge_owner (V5) would let any
-- connection under that membership read every tenant's rows with no policy applying -
-- FORCE is what stops ownership from being an implicit authorization decision. This is
-- also what ADR-007 §5.4 requires whenever the owner and the pipeline role could ever
-- be the same principal; no ADR amendment needed, this complies with the sentence as
-- written.
ALTER TABLE notification_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_events FORCE ROW LEVEL SECURITY;
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE deliveries FORCE ROW LEVEL SECURITY;
ALTER TABLE delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE delivery_attempts FORCE ROW LEVEL SECURITY;

-- Every policy is scoped TO challenge_api explicitly. With FORCE on, an unscoped
-- policy would apply to challenge_owner too - the pipeline's exemption must stay the
-- one named mechanism (its BYPASSRLS grant in V5), never a side effect of how a policy
-- happened to be scoped.
--
-- current_setting('app.client_id', true) - the second argument true is mandatory. It
-- returns NULL when the session variable was never set (TASK-008-10 binds it), and
-- client_id = NULL matches no row: a query that skipped the session binding returns
-- zero rows, never another tenant's rows. Without true, an unset variable raises an
-- error instead - fail closed, not fail with an exception (ADR-007 §5.4).
CREATE POLICY tenant_isolation ON notification_events
  FOR SELECT TO challenge_api
  USING (client_id = current_setting('app.client_id', true));

CREATE POLICY tenant_isolation ON subscriptions
  FOR SELECT TO challenge_api
  USING (client_id = current_setting('app.client_id', true));

CREATE POLICY tenant_isolation ON deliveries
  FOR SELECT TO challenge_api
  USING (client_id = current_setting('app.client_id', true));

-- delivery_attempts has no client_id column: express visibility against the parent
-- deliveries row instead - an attempt is visible exactly when its delivery is.
CREATE POLICY tenant_isolation ON delivery_attempts
  FOR SELECT TO challenge_api
  USING (
    EXISTS (
      SELECT 1 FROM deliveries d
      WHERE d.delivery_id = delivery_attempts.delivery_id
        AND d.client_id = current_setting('app.client_id', true)
    )
  );

-- No INSERT/UPDATE/DELETE policy: challenge_api holds SELECT only (V5), so a write
-- policy would guard a privilege that does not exist (YAGNI).

COMMENT ON POLICY tenant_isolation ON notification_events IS
  'ADR-007 §5.4: fail-closed tenant read filter for challenge_api. current_setting(..., true) yields NULL, never an error, when app.client_id was never bound (TASK-008-10).';
COMMENT ON POLICY tenant_isolation ON subscriptions IS
  'ADR-007 §5.4: fail-closed tenant read filter for challenge_api.';
COMMENT ON POLICY tenant_isolation ON deliveries IS
  'ADR-007 §5.4: fail-closed tenant read filter for challenge_api.';
COMMENT ON POLICY tenant_isolation ON delivery_attempts IS
  'ADR-007 §5.4: no client_id column on this table - visibility follows the parent deliveries row via EXISTS, same fail-closed predicate.';
