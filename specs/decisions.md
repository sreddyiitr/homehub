# HomeHub — Decisions, Open Questions, Verification

## Open questions (need an answer from the owner)

**2026-11-11: the owner accepted every recommendation below.** They are now
binding decisions. The table stays as the record of why.

| ID | Question | Recommendation | Affects |
|---|---|---|---|
| OQ-1 | CLAUDE.md requires `enduser.id` but doesn't define auth. How is the caller identified? | Header `X-HomeHub-User: BUY-004`, with the role taken from the prefix. Explicitly a stand-in; Cognito is a possible later lesson. | R-LIST-02, R-OFFER-07, R-OBS-02, design §4.1 |
| OQ-2 | Offer workflow: what status does a *valid* offer get after async validation? How does a buyer respond to a counter? | Add `UNDER_REVIEW`. v1: buyers can't respond to counters (the agent can still reject). Later: `POST /offers/{id}/accept-counter`. | R-OFFER-02, design §5.2 |
| OQ-3 | `ListingSold` exists as an event, but no endpoint moves a listing to SOLD. Is UNDER_OFFER→ACTIVE (deal falls through) needed? | Add `POST /listings/{id}/sold` (UNDER_OFFER→SOLD). Leave "reopen" out of v1. | R-LIST-06, design §4.2 |
| OQ-4 | Should search return `UNDER_OFFER` listings? | Yes (common in real estate as "pending"), shown with a status badge. They still can't get new offers accepted, because validation requires ACTIVE. | R-SRCH-01 |
| OQ-5 | Repeated price drops: alert a saved search every time, or only once per listing? | Alert again only if the new price is lower than the price last alerted for that listing (`lastNotified` map). | R-ALERT-03.2 |
| OQ-6 | CLAUDE.md says resource names are Java constants **and** SSM holds "resource names". Which is the source of truth, and how do shell scripts (`deploy-frontend.sh`) get the names? | The Java constants are the source of truth. Bootstrap writes them to SSM `/homehub/local/resources/*`, and scripts read SSM (with the endpoint check). The app never reads names from SSM. | R-CFG-03 |
| OQ-7 | The buyer view needs "my offers", but there's no endpoint for it. | Add `GET /buyers/{id}/offers` (uses `buyer-index`). | R-FE-03, design §4.2 |
| OQ-8 | CLAUDE.md: `MessageDeduplicationId=offerId`. With that, an accept within 5 minutes of submission would be dropped as a duplicate. | Use `{offerId}:{COMMAND}`, and update the CLAUDE.md resource table. | design §6.1 |
| OQ-9 | "App keeps working with the collector down": automated test or manual check? | Automated IT: stop the collector container and assert that a request succeeds in under 1s. | R-OBS-10 |
| OQ-10 | `/offers/{id}/…` has only offerId, but the Offers table key is (listingId, offerId). | Add a KEYS_ONLY GSI `offer-id-index`. That keeps the CLAUDE.md URL shape. | design §3.1 |
| OQ-11 | `homehub-documents` (offer PDFs) is a listed resource, but no endpoint or flow uses it. | Create the bucket in bootstrap. Add `POST /offers/{id}/documents/upload-url` only if you want it; otherwise defer. | design §3.2 |
| OQ-12 | Playwright needs an instrumented build on S3, but "the instrumented build is never deployed for normal use". | A separate bucket `homehub-web-e2e`, used only by `make test-functional`. | R-TEST-04 |
| OQ-13 | The fixtures have agents and buyers, but there's no table for them. Should the API validate that a `BUY-…`/`AGT-…` exists? | v1: no table. Validate only the id pattern; referential integrity is checked in the fixture test. Add a `Users` table later if auth arrives. | R-SEED-04 |
| OQ-14 | What is the geocoding API key used for? | Load it and report it in health as `present: true`. Make no outbound call. Listings carry optional lat/lon supplied by the agent. | R-CFG-05 |

### Collector changes (originally made in `../homehub-otel-collector`; now in `otel-collector/`, D-13)

| ID | Proposal | Status |
|---|---|---|
| P-1 | Add a `filter/noise` processor to the traces + metrics pipelines: drop SDK self-metrics (scope `io.opentelemetry.sdk.*` / `io.opentelemetry.exporters.*`) SERVER spans for `/actuator`, `/v3/api-docs`, `/swagger-ui`, and CORS preflight `OPTIONS` requests. Agent 2.33.1 can't disable these via env. Validated 2026-11-11 with `homehub-otel:local validate` AND a throwaway collector run: only user-request spans and `http.server.request.duration` remained. Config as applied: | **Applied 2026-11-11** at the owner's request: `otel-collector.yaml` + its CLAUDE.md updated, image rebuilt (`homehub-otel:local` / `:0.161.0`), verified end to end. |

```yaml
processors:
  filter/noise:
    error_mode: ignore
    metrics:
      metric:
        - 'IsMatch(instrumentation_scope.name, "^io\\.opentelemetry\\.(sdk|exporters)\\.")'
    traces:
      span:
        - 'kind == SPAN_KIND_SERVER and IsMatch(attributes["url.path"], "^/(actuator|v3/api-docs|swagger-ui)")'
        - 'kind == SPAN_KIND_SERVER and attributes["http.request.method"] == "OPTIONS"'   # CORS preflights
# then: processors: [memory_limiter, filter/noise, batch]  in the traces and metrics pipelines
```

## Decisions (accepted)

| ID | Decision | Rationale |
|---|---|---|
| D-1 | Agent accept/reject/counter go through the FIFO queue (202), not a direct write. | Per-listing ordering serializes competing accepts. This is the main lesson FIFO teaches. |
| D-2 | Search uses a sparse GSI (`searchCity` set only for searchable statuses). | DRAFT/SOLD are excluded by construction. Query, never Scan. |
| D-3 | One JVM hosts the API and all workers. | Simpler local setup. design §1 explains the real-AWS split. |
| D-4 | Simplified outbox (`lastCommandId` + `eventPublished`) for idempotency. | At-least-once delivery. The design explains DynamoDB Streams as the alternative. |
| D-5 | Integer USD for money. | Avoids floating-point problems; cents aren't needed for home prices. |
| D-6 | RFC 9457 problem+json for errors. | Standard, and Spring 6 supports it natively. |
| D-8 | `make telemetry-clean` stops the collector, empties the files, then starts it again. | Truncating the files from the host while the collector runs leaves NUL-filled gaps (it keeps writing at its old offset through the Docker Desktop bind mount). Verified 2026-11-11. CLAUDE.md's "collector keeps running" is not achievable with the current collector. |
| D-9 | OTel API pinned to 1.67.0 (what agent 2.33.1 is built on), overriding Spring Boot's 1.49.0. The agent version lives ONLY in `pom.xml` (`otel.agent.version`); `otel-env.sh` reads it and checks the jar's manifest version (so a hand-downloaded jar works). Log key/value attributes are allowlisted to `homehub.*`. | The agent can only bridge API calls up to its own version. The allowlist means an accidental `addKeyValue("email", …)` never reaches telemetry. |
| D-10 | Telemetry is "focused" on user actions by default: no JVM runtime metrics, delta metric temporality, framework logs at WARN. `HOMEHUB_TELEMETRY=verbose` restores everything. | Owner request 2026-11-11 ("I just want user actions"). Deviates from CLAUDE.md's "plus auto JVM/HTTP metrics": HTTP metrics stay, JVM metrics are opt-in. |
| D-11 | Browser tracing: explicit **user-action spans** (`data-action` clicks + `open-app`) with the API fetch run inside the action's context. No Zone.js, user-interaction, or document-load instrumentation. | Owner wants "only user actions". Zone.js can't follow React 19 scheduling + native async/await, so click and fetch landed in separate traces (verified in headless Chromium). Deviates from CLAUDE.md's "document-load + fetch instrumentation". |
| D-12 | `scripts/emulator-env.sh` **reuses** a running emulator: `EMULATOR_CONTAINER` if set, else the single running container of `EMULATOR_IMAGE` (default `aws-emulator:latest`), with ports and the credentials folder read via `docker port`/`docker inspect`. Several running → error asking to choose. None → start `homehub-emulator` (label `homehub.managed=true`, port 9297, telemetry off). `make down` removes only labelled containers. | Owner request: use their own emulator container by default, with no names hardcoded, so teammates can run the repo unchanged. A reused emulator keeps its owner's telemetry setting (only reported). Tested 2026-11-11: auto-detect, explicit override, several running, start-own, host port ≠ container port, missing mount, `make down` safety. |
| D-13 | The collector moved into this repo: `otel-collector/` (Dockerfile + config + README). `scripts/otel-env.sh` validates and builds `homehub-otel:local` when missing or when the folder changed (hash label `homehub.config.sha`), and replaces a running collector on an older image. Ports are published on 127.0.0.1 only (was all interfaces). | Owner request 2026-11-11: one repo, no separate build step for teammates. The sibling project is left untouched. Tested: first build, reuse, config edit → rebuild, invalid config → clear error with the running collector untouched, image missing → built. |
| D-7 | Created entities get `<PREFIX>-<ULID>` ids. | Never collides with fixture ids; sortable by time. |

## Phase 1 verification checklist (emulator)

When an item is answered, record it here **and** replace the TO VERIFY list in
CLAUDE.md. Record: answer, how it was verified (command/output), date.

| ID | Verify | Answer |
|---|---|---|
| V-1 | Endpoint URL + port of the container (`docker ps`) | `https://127.0.0.1:<port>` (default port 9297, `-p` flag). Health: `GET /__admin/health` → `ok`. Image `aws-emulator:latest`, v1.0.322 (2026-11-10). Verified 2026-11-11 via `docker logs`, curl. |
| V-2 | HTTP vs HTTPS; cert handling for SDK, CLI, browser | **HTTPS only**, using a self-signed CA generated per run: `~/.aws/emulator/<port>/ca.pem` (+ `truststore.p12` for Java). CLI: `ca_bundle` in the generated config. Java: we load `ca.pem` into the SDK HTTP client's trust manager (per client, not JVM-global). Browser: not yet verified (only needed for presigned URLs, Phase 4). |
| V-3 | How to obtain the current EMULATOR* credentials | The emulator writes `~/.aws/emulator/<port>/emulator_credentials` (INI, `[default]`) on every start. The container needs `HOME=$HOME` and a bind mount of `~/.aws/emulator` so the file lands on the host. Wrong keys → 403 `UnrecognizedClientException`. `--auth off` exists, but we keep auth on. |
| V-4 | S3 website hosting (PutBucketWebsite + website endpoint) and PutBucketCors | |
| V-5 | Fidelity scores: s3, dynamodb, sqs, sns, events, cloudwatch, logs, secretsmanager, ssm | |
| V-6 | S3 event notifications → SQS (PutBucketNotificationConfiguration, delivery) | |
| V-7 | SNS → SQS subscription, raw message delivery, message attributes | |
| V-8 | EventBridge: SQS FIFO target with MessageGroupId, SNS target, input transformer | |
| V-9 | DynamoDB Streams (only needed if D-4 is upgraded) | |
| V-10 | SQS FIFO: dedup ID, group ordering, redrive to FIFO DLQ, `ApproximateReceiveCount` | |
| V-11 | DynamoDB conditional writes (`ConditionalCheckFailedException`), GSIs incl. sparse | |
| V-12 | Presigned PUT/GET: does the emulator validate signature and expiry? Signed `x-amz-meta-*` headers | |
| V-13 | CloudWatch PutMetricData, PutMetricAlarm; Logs PutLogEvents | |
| V-15 | Region handling (no region flag) | The emulator accepts any region, and **regions are isolated**: a table created in us-west-2 is invisible in us-east-1. The generated config defaults to us-east-1, so we always set `AWS_REGION=us-west-2` explicitly. |
| V-16 | Emulator telemetry | On by default. `--telemetry off` always wins. The emulator logs its state at startup (`usage and error telemetry: enabled/disabled`), which emulator-env.sh reads to warn when a reused container has it on. |
| V-17 | Status code for unimplemented operations | **HTTP 501 `NotImplemented`**, message "operation X is scaffolded but not implemented" (GameLift `ListFleets`, 2026-11-11). CLAUDE.md had said 503; `EmulatorCalls` now treats 501 and 503 as "not supported". DynamoDB `DescribeContributorInsights`, `ListBackups`, `DescribeKinesisStreamingDestination` returned 200. |
| V-18 | Official env setup | Per the [SDK guide](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html): `AWS_SHARED_CREDENTIALS_FILE` + `AWS_CONFIG_FILE` (not key env vars), `AWS_EC2_METADATA_DISABLED`, `AWS_DISABLE_HOST_PREFIX_INJECTION`; verify with `aws sts get-caller-identity` → account `000000000000`. Clean stop (SIGINT/SIGTERM) deletes the port's files; a crash leaves them. emulator-env.sh follows this since 2026-11-11. |
| V-14 | Secrets Manager GetSecretValue; SSM GetParametersByPath | |

Any 501/503 found here → R-OPS-04: name the operation, propose a workaround,
and record it in this table.
