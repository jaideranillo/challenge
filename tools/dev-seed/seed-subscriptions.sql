-- Local-demo subscription seed. NOT a Flyway migration on purpose: subscription CRUD is
-- explicitly out of scope (ADR-003 §3), and Flyway's classpath:db/migration runs in every
-- environment including prod (application.yaml) - seed data has no business living there.
--
-- CLIENT001 has two subscriptions (credit_* events / debit_* events) to demo per-client fan-out
-- to multiple targets; CLIENT002 and CLIENT003 have one each covering every event_type the local
-- Event Generator (adapter/in/web/local/eventgenerator) emits. All point at the local webhook
-- stub. Fixed subscription_id per row + ON CONFLICT DO UPDATE makes this safe to re-run after a
-- DB reset or on every container start (see tools/dev-seed/seed-subscriptions.sh / make up).
--
-- Requires CHALLENGE_WEBHOOK_SECRETS_DEMO to be exported before bootRun (secret_ref='demo' below
-- must resolve or every delivery fails closed - see WebhookSecretPort).
--
-- Run: docker exec -i <postgres-container> psql -U myuser -d mydatabase < tools/dev-seed/seed-subscriptions.sql

INSERT INTO subscriptions (
    subscription_id, client_id, target_url, secret_ref, event_types, active, verification_state
)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'CLIENT001',
     'http://localhost:8080/local/webhook-stub/receive', 'demo',
     ARRAY['credit_card_payment','credit_transfer','credit_refund','credit_deposit','credit_cashback'],
     true, 'VERIFIED'),
    ('00000000-0000-0000-0000-000000000002', 'CLIENT001',
     'http://localhost:8080/local/webhook-stub/receive', 'demo',
     ARRAY['debit_card_withdrawal','debit_automatic_payment','debit_transfer','debit_purchase',
           'debit_subscription'],
     true, 'VERIFIED'),
    ('00000000-0000-0000-0000-000000000003', 'CLIENT002',
     'http://localhost:8080/local/webhook-stub/receive', 'demo',
     ARRAY['credit_card_payment','debit_card_withdrawal','credit_transfer','debit_automatic_payment',
           'credit_refund','debit_transfer','credit_deposit','debit_purchase','credit_cashback',
           'debit_subscription'],
     true, 'VERIFIED'),
    ('00000000-0000-0000-0000-000000000004', 'CLIENT003',
     'http://localhost:8080/local/webhook-stub/receive', 'demo',
     ARRAY['credit_card_payment','debit_card_withdrawal','credit_transfer','debit_automatic_payment',
           'credit_refund','debit_transfer','credit_deposit','debit_purchase','credit_cashback',
           'debit_subscription'],
     true, 'VERIFIED')
ON CONFLICT (subscription_id) DO UPDATE SET
    client_id = EXCLUDED.client_id,
    target_url = EXCLUDED.target_url,
    secret_ref = EXCLUDED.secret_ref,
    event_types = EXCLUDED.event_types,
    active = EXCLUDED.active,
    verification_state = EXCLUDED.verification_state,
    updated_at = now();
