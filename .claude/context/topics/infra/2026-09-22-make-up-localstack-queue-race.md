# Gotcha: `make up` can race LocalStack queue creation on a fresh recreate

**Date:** 2026-09-22

## Symptom

After `make down` followed immediately by `make up` on a fresh container recreate, the app
sometimes fails to start with:

```
software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException: The specified queue does not exist.
	at ...DeliveryQueueListener.<init>
```

`make up`'s health wait loop then times out (`App did not become healthy in time`).

## Root cause

`compose.yaml`'s `localstack` service has a healthcheck (`curl .../_localstack/health`), and
`make infra-up` only waits for `docker compose up -d` to return — it does not wait for the
healthcheck to pass, nor for `docker/localstack/init-sqs.sh` (which creates the `deliveries` /
`deliveries-dlq` queues on container ready) to finish. On a slow start, `app-up` can launch before
the queues exist, and `DeliveryQueueListener`'s constructor resolves the queue URL eagerly
(`getQueueUrl`), so it fails fast.

## Workaround

Re-run the app after confirming queues exist:

```bash
docker exec challenge-localstack-1 awslocal sqs list-queues
# then just restart the app process (infra is already up, no need to redo make down/up)
```

Not yet fixed at the Makefile level (would need a queue-existence wait step in `infra-up` /
`wait-app`, mirroring the existing Postgres/app health wait pattern). Worth a small devops task if
this keeps recurring.
