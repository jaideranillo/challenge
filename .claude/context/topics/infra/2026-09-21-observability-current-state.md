# Observability: what actually works today vs. what's missing

Checked live (bootRun, local profile) after a report that "nothing is visible, no metrics/logs/traces."

## Metrics and traces already work, automatically

`spring-boot-starter-opentelemetry` is in `build.gradle`, and `spring-boot-docker-compose` auto-wires
its OTLP exporter endpoint to the running `grafana-lgtm` container's ports (4317/4318) via service
connection — no manual config needed. Confirmed in boot log: `Publishing metrics for
OtlpMeterRegistry every 1m to http://127.0.0.1:<mapped-port>/v1/metrics`. Traces flow the same way.
View at `http://localhost:3000` (Grafana; Mimir for metrics, Tempo for traces).

## Logs do not reach Loki — this is the real gap

No `logback-spring.xml`/`logback.xml` and no `management.otlp.logging.export.*` (or equivalent)
config exists anywhere in `application*.yaml`. Application logs stay on stdout only. If/when this
gets planned (user said they'll do this as a separate piece of work, likely via an ADR), the fix is
adding an OTLP log appender/exporter config — not re-doing metrics/traces, which already work.

## Fast, no-setup way to check "did the event reach the queue" without any of the above

LocalStack SQS can be queried directly, no observability stack required:

```bash
docker exec -it $(docker ps -qf name=localstack) awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/deliveries \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  --region us-east-1
```

Queue names (from `docker/localstack/init-sqs.sh`): main `deliveries`, DLQ `deliveries-dlq`.
`ApproximateNumberOfMessages` = waiting; `ApproximateNumberOfMessagesNotVisible` = in flight (a
worker has it, pending ack/visibility timeout).
