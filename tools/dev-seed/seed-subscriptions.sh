#!/usr/bin/env bash
# Loads tools/dev-seed/seed-subscriptions.sql into the local compose Postgres.
# Local demo use only. Requires the compose postgres service running (docker compose up postgres).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="$(docker ps -qf name=postgres)"

if [[ -z "${CONTAINER}" ]]; then
  echo "Error: no running postgres container found (docker compose up postgres first)" >&2
  exit 1
fi

docker exec -i "${CONTAINER}" psql -U myuser -d mydatabase < "${SCRIPT_DIR}/seed-subscriptions.sql"

echo "Seeded CLIENT001/002/003 subscriptions -> http://localhost:8080/local/webhook-stub/receive"
echo "Reminder: export CHALLENGE_WEBHOOK_SECRETS_DEMO=<any-value> before bootRun, or deliveries fail closed."
