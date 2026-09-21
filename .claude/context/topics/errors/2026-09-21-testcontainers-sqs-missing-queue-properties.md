# Error: TestcontainersConfiguration missing challenge.sqs.queues.* broke 167 tests repo-wide

**Symptom:** After `SqsClientConfig`/`SqsProperties` landed as real Spring beans, any `@SpringBootTest` not running under the `local` profile failed context load with a binding error on `SqsProperties.queues` (`@NotNull`).

**Root cause:** `TestcontainersConfiguration`'s `DynamicPropertyRegistrar` for the LocalStack SQS container only registered `challenge.sqs.endpoint`/`region`/`credentials.*` (the container-derived values). Queue names (`challenge.sqs.queues.deliveries`, `.deliveries-dlq`) aren't a container property — they're a fixed application concern that lives in `application-local.yaml` and `docker/localstack/init-sqs.sh`'s hardcoded queue names (`"deliveries"`, `"deliveries-dlq"`) — and nothing copied them into the test registrar.

**Fix:** add both queue-name keys to `sqsPropertiesRegistrar` in `TestcontainersConfiguration.java`, matching the literal names `init-sqs.sh` creates.

**General lesson:** when wiring a `DynamicPropertyRegistrar` for a new adapter's test config, enumerate every `@NotNull`/required field on that adapter's `@ConfigurationProperties` record and check each one is registered — not just the ones the Testcontainers container object itself exposes as getters. It's easy to miss config that's a fixed literal rather than something read off the container.
