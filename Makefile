.PHONY: up down restart logs seed run test build clean status

COMPOSE      := docker compose -f compose.yaml
APP_LOG      := .run/app.log
APP_PID      := .run/app.pid
WEBHOOK_SECRET ?= demo-secret-value

# Full local dev stack: infra containers + app (local profile) + demo subscription seed.
# Re-running is safe: the seed is idempotent (fixed UUIDs, ON CONFLICT DO UPDATE).
up: infra-up wait-queues app-up wait-app seed
	@echo ""
	@echo "Ready:"
	@echo "  App      http://localhost:8080"
	@echo "  Grafana  http://localhost:3000"
	@echo "  Logs     make logs"
	@echo "  Stop     make down"

infra-up:
	@mkdir -p .run
	$(COMPOSE) up -d

# Known race: on a fresh container, the app can start (and its DeliveryDlqConsumer/
# DeliveryQueueListener beans eagerly resolve queue URLs) before docker/localstack/init-sqs.sh
# finishes creating the SQS queues, crashing the app before `wait-app`/`seed` ever run.
wait-queues:
	@echo "Waiting for LocalStack queues..."
	@for i in $$(seq 1 30); do \
		if docker exec challenge-localstack-1 awslocal sqs list-queues 2>/dev/null | grep -q deliveries; then \
			echo "Queues ready after $$((i*2))s"; exit 0; \
		fi; \
		sleep 2; \
	done; \
	echo "Queues did not appear in time - check LocalStack logs"; exit 1

app-up:
	@echo "Starting app (local profile)..."
	@CHALLENGE_WEBHOOK_SECRETS_DEMO=$(WEBHOOK_SECRET) nohup ./gradlew bootRun --args='--spring.profiles.active=local' > $(APP_LOG) 2>&1 & echo $$! > $(APP_PID)

wait-app:
	@echo "Waiting for app health..."
	@for i in $$(seq 1 60); do \
		code=$$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null); \
		if [ "$$code" = "200" ]; then echo "App up after $$((i*2))s"; exit 0; fi; \
		sleep 2; \
	done; \
	echo "App did not become healthy in time - check $(APP_LOG)"; exit 1

seed:
	./tools/dev-seed/seed-subscriptions.sh

down:
	@if [ -f $(APP_PID) ]; then kill $$(cat $(APP_PID)) 2>/dev/null || true; rm -f $(APP_PID); fi
	@pkill -f "gradlew bootRun" 2>/dev/null || true
	$(COMPOSE) down

restart: down up

logs:
	@tail -f $(APP_LOG)

status:
	@$(COMPOSE) ps
	@echo "---"
	@curl -s http://localhost:8080/actuator/health || echo "app not reachable"

test:
	./gradlew test

build:
	./gradlew build

clean:
	./gradlew clean
	rm -rf .run
