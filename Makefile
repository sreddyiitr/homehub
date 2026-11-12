# HomeHub — Phase 0 slice targets. More targets (infra, seed, deploy-frontend,
# coverage) arrive in their phases; see CLAUDE.md "Commands".
SHELL := /bin/bash
EMU   := source scripts/emulator-env.sh
JAVA  := source scripts/java-env.sh
OTEL  := source scripts/otel-env.sh

.PHONY: run down openapi frontend-dev test test-unit test-functional

run:                ## start emulator + API (bootstrap + seed on startup)
	./run-local.sh

# Only removes an emulator the scripts STARTED (label homehub.managed=true).
# `docker stop` (not rm -f) lets it shut down cleanly, so it deletes its own
# ~/.aws/emulator/<port>/ files; --rm then removes the container.
# An emulator you started yourself and we merely reused is never touched —
# stop that one with `docker stop <name>` if you want its state gone.
down:               ## stop the emulator container we started (its AWS state is lost — by design)
	@ids=$$(docker ps -aq --filter label=homehub.managed=true); \
	if [ -n "$$ids" ]; then docker stop $$ids >/dev/null; docker rm -f $$ids >/dev/null 2>&1; echo "stopped HomeHub-managed emulator"; \
	else echo "no HomeHub-managed emulator running (a reused one is left alone)"; fi

openapi:            ## refresh frontend/openapi/homehub-api.json from the RUNNING app
	curl -sf localhost:8080/v3/api-docs | python3 -m json.tool > frontend/openapi/homehub-api.json
	cd frontend && npm run gen:api

frontend-dev:       ## Vite dev server on :5173 (API must be running)
	cd frontend && npm run dev

test: test-unit test-functional

test-unit:          ## backend + frontend unit tests (no emulator)
	$(JAVA) && cd backend && mvn -q -B test
	cd frontend && npm test

test-functional:    ## backend ITs against the emulator, app JVM runs WITH the OTel agent
	$(EMU) && $(OTEL) && $(JAVA) && cd backend && mvn -q -B verify

.PHONY: telemetry telemetry-clean collector collector-logs

collector:          ## (re)build the collector image from otel-collector/ and (re)start it
	@source scripts/otel-env.sh && docker port homehub-otel

collector-logs:     ## live one-line-per-batch view of what the collector receives
	docker logs -f homehub-otel

telemetry:          ## summarize telemetry/*.jsonl (span trees, logs, metric names)
	@python3 scripts/telemetry_summary.py --last 5

# The collector must be stopped while we empty the files: truncating them
# from the host while it runs leaves NUL-filled gaps (it keeps writing at its
# old offset through the Docker Desktop bind mount). AWS state is untouched.
telemetry-clean:    ## empty telemetry/ (restarts the collector container)
	docker stop homehub-otel >/dev/null
	for f in telemetry/traces/traces.jsonl telemetry/metrics/metrics.jsonl telemetry/logs/logs.jsonl; do : > $$f; done
	rm -f telemetry/*/*.jsonl.*  # rotated backups
	docker start homehub-otel >/dev/null
	@for i in $$(seq 1 30); do curl -sf localhost:13133 >/dev/null && echo "telemetry cleaned; collector healthy" && exit 0; sleep 1; done; echo "collector not healthy"; exit 1
