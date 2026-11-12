# CLAUDE.md — HomeHub

Real estate listing & offer management service. A learning project: Spring Boot
backend + React frontend, developed and tested entirely against **AWS Emulator**
running locally in Docker. The repo owner is learning AWS service patterns —
explain design decisions as you make them.

## What HomeHub does

Agents publish property listings with photos. Buyers search listings, save
searches, and submit offers. Offers are processed asynchronously (validated,
accepted/rejected/countered); buyers get alerts when listings match their saved
searches or their offer status changes.

## Repo map (monorepo)

```
homehub/
  CLAUDE.md          # this file
  Makefile           # run, infra, seed, test, deploy-frontend targets
  run-local.sh       # starts emulator (if needed) + app, prints URLs
  backend/           # Spring Boot service (Maven)
  frontend/          # React + Vite + TypeScript SPA
  infra/
    bootstrap/       # resource-creation code shared by app startup & make infra
    iam/             # least-privilege IAM policy JSON (NOT enforced locally)
    cfn/             # (later phase) CloudFormation/CDK for the same infra
  test/data/         # checked-in fixtures: agents, buyers, listings, offers,
                     # saved-searches (JSON) + photos/ (small placeholder images)
  otel-collector/    # OTel Collector image: Dockerfile + otel-collector.yaml + README
  scripts/           # emulator-env.sh, otel-env.sh, java-env.sh, telemetry_summary.py
  specs/             # spec-driven development: requirements, design, tasks, decisions
  otel/              # OTel Java agent jar, auto-downloaded by scripts/otel-env.sh (gitignored)
  telemetry/         # OTel output: traces/, metrics/, logs/ (gitignored)
```

**Collector lives here:** `otel-collector/` holds the collector image's
Dockerfile and pipeline config (moved in from the former sibling project
`../homehub-otel-collector` on 2026-11-11, at the owner's request).
`scripts/otel-env.sh` builds `homehub-otel:local` from it automatically.
See "Observability" below for the contract.

Two containers, each started with plain `docker run` by `run-local.sh`
(no Docker Compose): **AWS Emulator** and **OTel Collector**. The Spring Boot
app runs directly on the host.

**Hard rule:** frontend and backend communicate only via the HTTP API. The
frontend never imports backend code; its TypeScript types are generated from
the backend's OpenAPI spec as a build step.

## Tech stack

- **Backend:** Java 21, Spring Boot 3.x, Maven, AWS SDK for Java v2 /
  Spring Cloud AWS. JUnit 5 integration tests run against the emulator.
- **Frontend:** React (latest stable) + Vite + TypeScript, React Router,
  TanStack Query. Built to static assets, deployed to S3 (`homehub-web`).
- **Local cloud:** AWS Emulator (Docker container). No real AWS account is
  used for development or tests.

## AWS Emulator — facts and conventions

AWS Emulator was released 2026-11-11 (after your training cutoff). **Do not
rely on memory about it.** Docs: https://github.com/aws/aws-emulator and
https://docs.aws.amazon.com/emulator/latest/userguide/

Verified facts (from the launch docs):

- Local emulator of the AWS APIs; serves an AWS API endpoint the CLI/SDKs
  point at via environment variables. No GUI.
- **In-memory only — no persistence.** All resources are deleted when the
  emulator stops. Infra creation is therefore idempotent-on-startup, never
  "one time".
- **Single account (000000000000), single region per emulator instance.**
  We use **us-west-2** everywhere (the region AWS verifies the emulator
  against; global services verified in us-east-1).
- **Credentials:** the emulator generates NEW keys on every start; access key
  IDs start with `EMULATOR` and requests signed with any other keys are
  rejected. `run-local.sh` must obtain the current keys from the running
  emulator and export them before starting the app. Never hardcode keys.
- **No IAM evaluation.** The emulator does not evaluate IAM or
  resource-based policies the way AWS does — a request AWS would deny can
  succeed locally. We still write least-privilege policies in `infra/iam/`,
  but nothing local validates them.
- **Unimplemented operations return HTTP 501 `NotImplemented`** ("operation
  X is scaffolded but not implemented" — verified 2026-11-11; this file
  originally said 503, so code treats 501 and 503 alike). If you hit one,
  STOP, report which operation, and propose a workaround. Never silently
  stub around it or fake a success path.
- **Don't validate the generated key format** (the guide: it can change in
  any release). IDs currently start with `EMULATOR`.
- Fidelity varies by service/feature; per-service scores ship with each
  release (aws-emulator-evaluation-suite). Not for production or security
  testing. No throttling/outage simulation.
- Telemetry is on by default. An emulator our scripts START runs with it
  off (`--telemetry off`); an emulator you started yourself is REUSED as-is
  and the scripts only print a note if its telemetry is on (owner request,
  2026-11-11 — see specs/decisions.md D-12).

TO VERIFY in Phase 1 (then replace this list with the answers):

- [x] Endpoint: `https://127.0.0.1:<host port>`. `scripts/emulator-env.sh`
      reuses the ONE running `aws-emulator` container (any name/port), else
      starts `homehub-emulator` on 9297. Override: `EMULATOR_CONTAINER`,
      `EMULATOR_IMAGE`, `EMULATOR_PORT` (see specs/decisions.md V-1, D-12)
- [x] HTTPS only, per-run CA at `~/.aws/emulator/<port>/ca.pem` (V-2)
- [x] Credentials: `~/.aws/emulator/<port>/emulator_credentials`, rewritten
      each start; `run-local.sh` exports them (V-3). Regions are isolated —
      always set `AWS_REGION=us-west-2` (V-15)
- [ ] Whether S3 static website hosting (PutBucketWebsiteConfiguration +
      website endpoint) and bucket CORS (PutBucketCors) are supported
- [ ] Fidelity scores for: s3, dynamodb, sqs, sns, events (EventBridge),
      cloudwatch, logs, secretsmanager, ssm

## Configuration & profiles

- **`local`** (default for dev): endpoint override from `AWS_ENDPOINT_URL`,
  region us-west-2, `homehub.bootstrap-infra: true`, `homehub.seed-data: true`.
- **`aws`**: no endpoint override (default credential chain), bootstrap and
  seeding **disabled**. The app NEVER creates infrastructure or loads test
  data against real AWS — real infra comes from `infra/cfn/`.
- Endpoints are config-driven only — never hardcoded in Java, TS, or scripts.
- All resource names (buckets, tables, queues, topics, bus, log groups,
  parameter/secret names) are constants in ONE backend config class; scripts
  and tests reference the same names.

## Startup sequence (local profile) — automatic, strict order

1. **Infra bootstrap** (`@Order(1)` ApplicationRunner, code in
   `infra/bootstrap/`): create buckets, DynamoDB tables + GSIs, SQS queues
   with DLQs (maxReceiveCount=3), SNS topic + subscriptions, EventBridge bus
   + rules, secrets, SSM parameters, log groups, alarms. Idempotent — skip
   what exists.
2. **SeedDataLoader** (`@Order(2)`): load `test/data/*.json` into DynamoDB,
   upload `test/data/photos/` to the photos bucket. Idempotent via sentinel
   check (known listing id) — skips if already seeded.
3. Ready: `/actuator/health` reports each AWS dependency plus a `seedData`
   indicator (counts of staged listings/offers). Tests and Playwright wait
   on health before running.

This sequence re-runs safely on every app AND emulator restart.

## AWS resources

| Resource | Name | Purpose |
|---|---|---|
| S3 bucket | `homehub-listing-photos` | agent photo uploads (presigned PUT), thumbnails prefix, presigned GET for viewing |
| S3 bucket | `homehub-documents` | offer PDFs (disclosures, signed offers) |
| S3 bucket | `homehub-web` | static website hosting for the React SPA |
| DynamoDB | `Listings` | GSIs: city+price (search), agentId. Optimistic locking via `version` attribute |
| DynamoDB | `Offers` | PK listingId, SK offerId; GSI buyerId |
| DynamoDB | `SavedSearches` | buyer search criteria + notify flag |
| SQS | `photo-processing-queue` (+DLQ) | S3 photo event → thumbnail worker → update listing |
| SQS | `search-match-queue` (+DLQ) | listing ACTIVE / price drop → match against saved searches |
| SQS FIFO | `offer-processing-queue.fifo` (+DLQ) | offer workflow; MessageGroupId=listingId (per-listing ordering), MessageDeduplicationId=offerId (idempotency) |
| EventBridge | bus `homehub-events` | domain events: ListingPublished, PriceChanged, OfferSubmitted/Accepted/Rejected/Countered, ListingSold |
| SNS | `homehub-buyer-alerts` | buyer notifications; SQS subscription for test assertions |
| CloudWatch | namespace `HomeHub` | metrics: ListingsPublished, OffersSubmitted, OffersAccepted, PhotoProcessingFailures, offer-to-list ratio; alarms on PhotoProcessingFailures + DLQ depth |
| CW Logs | `/homehub/api` | structured JSON logs |
| Secrets Manager | demo third-party API key (geocoding) | loaded at startup |
| SSM Parameter Store | feature flags, resource names | loaded at startup |

### Offer workflow (the core async flow)

POST /offers → save as SUBMITTED → enqueue to FIFO queue → processor
validates (listing ACTIVE, amount > 0, no duplicate open offer from buyer)
→ update status with optimistic locking → emit domain event on
`homehub-events` → rules route: OfferAccepted → listing to UNDER_OFFER +
reject competing open offers; all outcomes → SNS buyer alerts.

## Test data (`test/data/`)

- Static, deterministic, checked in. No random generation at runtime.
- Fixed ids: `LST-0001…`, `AGT-001…`, `BUY-001…`, `OFR-0001…`.
- ~5 agents, ~8 buyers, ~20 listings across 4–5 cities (varied price tiers),
  ~15 offers in all states, ~6 saved searches (some matching, some not).
- Required edge cases: a DRAFT listing (unsearchable), a SOLD listing
  (rejects offers), a listing with multiple competing offers, a listing with
  no photos, offers above and below asking, one buyer with offers on several
  listings.
- Referential integrity is enforced by a validation test that loads the JSON
  and asserts every offer→listing/buyer and listing→agent reference resolves.
- Seed offers load in their final states directly — seeding never runs the
  queue pipeline. The async pipeline is exercised by tests and the UI.
- Integration tests assert against known fixture ids/values; only
  creation-flow tests make new entities.

## REST API

POST /listings · GET /listings/{id} · PATCH /listings/{id} ·
POST /listings/{id}/publish · POST /listings/{id}/photos/upload-url ·
GET /listings/{id}/photos · GET /listings/search?city=&minPrice=&maxPrice=&minBeds= ·
POST /listings/{id}/offers · GET /listings/{id}/offers ·
POST /offers/{id}/accept|reject|counter · POST /buyers/{id}/saved-searches ·
GET /buyers/{id}/saved-searches · GET /actuator/health

OpenAPI/Swagger UI enabled; the spec also feeds frontend type generation.

## Frontend

- Pages: listings grid (photos via presigned GETs, search filters), listing
  detail (gallery, offers, submit-offer form), buyer view (my offers with
  polled status, saved searches), agent view (create/edit/publish listings,
  browser photo upload via presigned PUT, accept/reject/counter offers).
- TanStack Query polling makes the async pipeline visible (offer status
  flips, thumbnail appears after upload).
- Deployed by `deploy-frontend.sh`: build + `aws s3 sync dist/` to
  `homehub-web` with correct Content-Type. SPA routing: error document →
  index.html.
- **CORS, two places:** Spring API (local profile) allows the UI origin;
  bucket CORS on `homehub-listing-photos` allows browser presigned PUTs.
- One Playwright smoke test runs against the S3-hosted UI (not the Vite dev
  server): load grid → open listing → submit offer → status eventually
  changes.

## Testing & coverage gates

Two test tiers, each with an enforced code-coverage threshold. **Builds fail
below threshold** — never lower a threshold, add `@Ignore`/`.skip`, or widen
exclusions to make a build pass; write the missing tests instead.

| Tier | Threshold | Backend | Frontend |
|---|---|---|---|
| Unit | **≥ 90%** line AND branch | JUnit 5, Mockito, AssertJ; JaCoCo via Surefire | Vitest + React Testing Library + MSW; Vitest v8 coverage |
| Functional | **≥ 75%** line | REST Assured + Awaitility against the running app + emulator; JaCoCo via Failsafe | Playwright E2E against the S3-hosted UI; Istanbul-instrumented build (vite-plugin-istanbul) |

### Backend
- **Unit tests** (`*Test.java`, Maven Surefire): no emulator, no network,
  no Spring context unless testing a slice (`@WebMvcTest`). AWS SDK clients
  are mocked. Cover services, validators, the offer state machine, event
  mapping, search-matching logic, error paths.
- **Functional tests** (`*IT.java`, Maven Failsafe): full app on the `local`
  profile against the emulator, called over HTTP with REST Assured. Async
  flows (photo processing, offer FIFO pipeline, EventBridge → SNS alerts)
  are asserted with Awaitility — no `Thread.sleep`. Assert against
  `test/data/` fixture ids.
- **Coverage:** separate JaCoCo reports per tier (`jacoco-ut.exec`,
  `jacoco-it.exec`), each with its own `check` rule: unit ≥ 90% line +
  branch, functional ≥ 75% line. Reports in `backend/target/site/`.
- **Architecture tests:** ArchUnit rules for layering (controllers →
  services → repositories; no AWS SDK types in controllers or domain).

### Frontend
- **Unit/component tests** (`*.test.tsx`, Vitest + RTL): components, hooks,
  API client, formatting utilities. Network mocked with MSW using handlers
  derived from the OpenAPI types. Threshold set in `vitest.config.ts`
  (`coverage.thresholds`: lines 90, branches 90, functions 90, statements 90).
- **Functional tests** (Playwright, `frontend/e2e/`): run against the
  S3-hosted build, not the Vite dev server. The functional build is
  Istanbul-instrumented; Playwright collects `window.__coverage__` per test
  and `nyc check-coverage --lines 75` enforces the threshold. The
  instrumented build is NEVER the one deployed for normal use.

### What counts / exclusions
- Excluded from coverage (must be listed explicitly in config, nothing
  else): generated OpenAPI types/clients, `*Application.java` main class,
  pure config/properties classes, records/DTOs with no logic, `main.tsx`.
- **Functional coverage also means flow coverage:** every REST endpoint and
  every user flow listed in the Frontend section has at least one
  functional test. Keep a table in `test/README.md` mapping endpoint/flow →
  test name; a missing row is a bug even if the % gate passes.

## Observability: OpenTelemetry → local files

All traces, metrics, and logs are emitted via OpenTelemetry and written to
`telemetry/` in the project, so they can be inspected (and asserted on)
without any vendor backend.

### Pipeline
```
backend (OTel Java agent / Spring Boot OTel starter) ─┐
frontend (OTel Web SDK, traces only)  ────────────────┼─ OTLP ─▶ OTel Collector ─▶ file exporters
                                                      ┘        (own container)     telemetry/traces/*.jsonl
                                                                                     telemetry/metrics/*.jsonl
                                                                                     telemetry/logs/*.jsonl
```
### The collector (`otel-collector/`)

The collector image is built from `otel-collector/` (details and editing
rules in `otel-collector/README.md`). Its contract — the app, the browser
and the tests rely on it; changing an item is a breaking change, so update
`scripts/otel-env.sh`, `VITE_OTEL_COLLECTOR_URL` and this table together:

| Item | Value |
|---|---|
| Image | `homehub-otel:local` (+ `:<OTELCOL_VERSION>`), built from `otel-collector/`; label `homehub.config.sha` = hash of its Dockerfile + config |
| OTLP gRPC | `localhost:4317` — backend (Java agent) |
| OTLP HTTP | `localhost:4318` — browser (OTel Web SDK); CORS allows localhost origins |
| Health | `curl -sf localhost:13133` |
| Mount | container `/telemetry` ← this repo's `./telemetry` |
| Files | `telemetry/traces/traces.jsonl`, `telemetry/metrics/metrics.jsonl`, `telemetry/logs/logs.jsonl` (OTLP JSON, one object per line, flushed ~1s, rotated 10 MB × 5) |

`scripts/otel-env.sh` (sourced by `run-local.sh`, `make test-functional`,
`make collector`):
1. builds the image if it's missing or its `homehub.config.sha` label doesn't
   match `otel-collector/` — after `validate`-ing the config with the pinned
   contrib image (an invalid config fails here; a running collector is left
   alone);
2. `mkdir -p telemetry/{traces,metrics,logs}` (the image has no shell);
3. reuses a running `homehub-otel` only if it mounts THIS repo's
   `telemetry/` (otherwise stops with an error) AND runs the current image
   (otherwise replaces it);
4. starts it with ports published on **127.0.0.1 only**:
   `docker run -d --name homehub-otel -p 127.0.0.1:4317:4317 -p 127.0.0.1:4318:4318 -p 127.0.0.1:13133:13133 -v "$PWD/telemetry:/telemetry" homehub-otel:local`;
5. polls `localhost:13133` until healthy (30s).

Collector config rules (from the former project; see `otel-collector/README.md`):
`memory_limiter` first in every pipeline; `filter/noise` before `batch` in
traces + metrics; batch timeout and file `flush_interval` ~1s (tests read
the files); `debug` exporter at `basic`; CORS localhost origins only, never
`*`; receivers bind `0.0.0.0` inside the container. Pin the contrib image
(`OTELCOL_VERSION`, never `latest`) and read the contrib changelog before
upgrading.

- **Networking:** the app runs on the host, so it exports to
  `localhost:4317`; the browser exports to `localhost:4318`. If the app is
  ever containerized, use `host.docker.internal` or a shared network.
- **Permissions:** the image runs as non-root. If no files appear in
  `telemetry/`, check the mount is writable before debugging anything else.
- `make collector-logs` (`docker logs -f homehub-otel`) shows a one-line-per-batch live summary.
- The collector drops background telemetry (`filter/noise`: SDK self-metrics,
  `/actuator`, `/v3/api-docs`, `/swagger-ui`, CORS `OPTIONS` spans), so the
  files hold user actions. Don't write tests that expect those spans.
- The collector is independent of the emulator: restarting the emulator
  doesn't touch telemetry, and `make telemetry-clean` doesn't touch AWS state.
- `telemetry/` and `otel/` are gitignored (keep a `.gitkeep` in `telemetry/`).
- Telemetry is best-effort: if the collector is down the app must keep
  working (exporter drops data; never block or fail requests on it).

### Backend instrumentation
- **OTel Java agent** (pinned version; `run-local.sh` downloads it to
  `otel/opentelemetry-javaagent.jar` if missing). It auto-instruments
  Spring MVC, the AWS SDK v2 (S3, DynamoDB, SQS, SNS, EventBridge calls
  appear as client spans), and logback — every invocation gets a span with
  no code. Application code depends only on `opentelemetry-api` and
  `opentelemetry-instrumentation-annotations` (for `@WithSpan`), never on
  the SDK.
- Configured ONLY via env vars in `scripts/otel-env.sh` (sourced by `run-local.sh` and `make test-functional`), never code. The agent version is set ONLY in `backend/pom.xml` `<otel.agent.version>` (currently 2.33.1); otel-env.sh reads it, downloads the jar to `otel/` when the jar's manifest `Implementation-Version` differs, and verifies the SHA-512. Bump `opentelemetry.version` (API) with it:
  ```
  OTEL_SERVICE_NAME=homehub-api
  OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=<git sha>
  OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
  OTEL_EXPORTER_OTLP_PROTOCOL=grpc     # set explicitly: the agent's default
                                       # protocol may be http/protobuf (:4318)
  OTEL_METRIC_EXPORT_INTERVAL=5000     # 5s so tests see metrics promptly
  java -javaagent:otel/opentelemetry-javaagent.jar ...
  ```
  Verify current agent defaults/env var names from the official docs
  before relying on them.
- Functional tests (Failsafe) run the app WITH the agent so the
  telemetry-oracle test has real data. Unit tests run without it.
- **Traces:** propagate W3C trace context end to end — browser → API →
  SQS message attributes → worker/processor → EventBridge event → SNS.
  One offer submission must appear as ONE connected trace across the async
  hops (use span links where a consumer batch handles many messages).
  Explain how propagation works through SQS and EventBridge.
- **Metrics:** OTel Meter for business metrics (`homehub.listings.published`,
  `homehub.offers.submitted`, `homehub.offers.accepted`,
  `homehub.photo.processing.failures`, `homehub.offer.to_list_ratio`
  histogram, queue processing latency histogram) plus auto JVM/HTTP metrics.
- **Logs:** structured JSON via logback, bridged to OTel so every log
  record carries `trace_id`/`span_id`.
- **Events:** each domain event (ListingPublished, OfferAccepted, …) is
  ALSO recorded as an OTel log record with `event.name` set, plus a span
  event on the active span — so `telemetry/logs/` has an audit stream of
  domain events correlated to traces.
- **CloudWatch stays.** CloudWatch metrics/logs/alarms remain as the
  AWS-native lesson; OTel is the vendor-neutral layer. Note in the README
  how on real AWS the collector would export to CloudWatch / X-Ray
  instead of files (same instrumentation, different exporter).

### Frontend instrumentation
- (Implemented as user-action spans + fetch — see specs/decisions.md D-11;
  mark clickable actions with `data-action="verb-noun"`.)
- OTel Web SDK: document-load + fetch instrumentation, exporting OTLP/HTTP
  to the collector (`:4318`). Collector `cors` config allows the UI origin.
  Fetch calls send `traceparent` so browser spans parent the API spans.
- `service.name=homehub-web`. Collector endpoint comes from a Vite env var.

### Instrumentation rules (apply in EVERY phase)

Auto-instrumentation gives every HTTP request and AWS call a span. Code adds
the business meaning. Every new feature must follow these rules; reviews
check them like tests.

**Who invoked** — on the server span of every API request (via a filter /
interceptor, once, not per controller):
- `enduser.id` (e.g., `BUY-004`, `AGT-002`), `homehub.actor.role`
  (`buyer` | `agent` | `system`), `client.address`, `user_agent.original`.
- Async consumers set `homehub.actor.role=system` and carry the ORIGINAL
  `enduser.id` through message attributes so the initiator stays visible.

**Which resource** — span attributes for every domain entity touched:
- `homehub.listing.id`, `homehub.offer.id`, `homehub.buyer.id`,
  `homehub.agent.id`, `homehub.saved_search.id`, `homehub.photo.key`.
- AWS resource details (table, queue URL, bucket, topic) come from
  auto-instrumentation — don't duplicate them.
- Note: "Resource" in OTel means the service emitting telemetry
  (`service.name`, etc.). Entity ids are span attributes, not Resource.

**Critical junctures** — own spans (`@WithSpan` or the Tracer API) for:
- Offer validation, offer state transitions, listing state transitions,
  saved-search matching, photo/thumbnail processing, each queue message
  handled, infra bootstrap, seed loading.
- Span names are low-cardinality verbs: `validate-offer`,
  `transition-offer-status`, `match-saved-searches` — NEVER ids in span
  names (ids go in attributes).
- Decision points add span events: `offer.rejected` with `reason`,
  `listing.status.changed` with `from`/`to`, `search.matched` with count,
  `optimistic-lock.retry` with attempt number.

**Errors** — every caught-and-handled or propagated failure:
- `span.recordException(e)` + `span.setStatus(StatusCode.ERROR, <short msg>)`
  on the span where it happened. Business rejections (e.g., offer on a SOLD
  listing) are NOT errors: use an event + `homehub.outcome=rejected`.
- Log the error once (with trace context, automatic via logback bridge),
  at the place it's handled — not at every layer it passes through.
- DLQ arrivals emit a `message.dead_lettered` event and increment
  `homehub.messages.dead_lettered`.

**Domain events** — every EventBridge domain event is ALSO an OTel log
record with `event.name` (`homehub.offer.accepted`,
`homehub.listing.published`, …) and the entity ids as attributes.

**Never put in telemetry:** emails, names, phone numbers, addresses of
buyers, credentials, presigned URLs (they're bearer tokens), full request
or response bodies. IDs only. A unit test scans recorded spans/logs from
an InMemory exporter for email/URL-signature patterns and fails if found.

**Testing the instrumentation:** unit tests for critical-juncture code use
`opentelemetry-sdk-testing` (InMemorySpanExporter / OpenTelemetryExtension)
to assert span names, attributes, events, and error status. This counts
toward the 90% unit gate like any other code.

### Telemetry as a test oracle
- A functional test submits an offer and then asserts, by reading
  `telemetry/traces/`, that a single trace contains the HTTP span, the
  SQS send/receive spans, and the processor span, and that
  `telemetry/logs/` contains an `OfferAccepted` (or Rejected) event record
  with the same `trace_id`. This proves propagation actually works.

## Commands

```
make run               # check collector image, start emulator + collector, export creds,
                       #   download OTel agent if missing, start app (local) with the agent
make down              # remove the emulator the scripts STARTED (never one you started yourself)
make infra             # bootstrap infra only (no app)
make seed              # seed test data only
make test              # all tests + all coverage gates
make test-unit         # backend + frontend unit tests, 90% gates
make test-functional   # backend ITs + Playwright vs. S3-hosted UI, 75% gates
make coverage          # open/print the four coverage reports
make deploy-frontend   # build SPA + sync to homehub-web
make telemetry-clean   # empty telemetry/ (briefly restarts the collector — see specs/decisions.md D-8)
HOMEHUB_TELEMETRY=verbose make run   # also JVM metrics + framework logs (D-10)
make collector         # build (if needed) + start the OTel collector only
make collector-logs    # live view of batches the collector receives
make telemetry         # summarize telemetry/*.jsonl: span trees, logs, metric names
```

## Specs (spec-driven development)

`specs/` is the source of truth for behavior: `requirements.md` (numbered
R-* requirements + acceptance criteria), `design.md`, `tasks.md` (per-phase
checklist), `decisions.md` (open questions, decisions, emulator verification).
Update the spec BEFORE the code; tests carry the AC id in their display name.
See `specs/README.md`.

## Build phases — STOP after each for review

1. Emulator research → fill in the TO VERIFY list above
2. Skeleton: profiles, AWS client config, health checks, bootstrap; prove
   connectivity with one S3 + one DynamoDB call. ALSO in this phase:
   start the collector container from `homehub-otel:local` (built from
   `otel-collector/`), the Java agent + env config, the who-invoked filter,
   and the PII-scan test (so every later phase is instrumented from day
   one); prove one request lands in all three telemetry files. Test tooling:
   Surefire/Failsafe split, both JaCoCo gates, ArchUnit. Gates are
   enforced from Phase 3 onward — every phase ships its own tests.
3. Listings CRUD + DynamoDB + search GSI
4. S3 photo upload flow + photo-processing worker + DLQ
5. Offer workflow: FIFO queue, processor, optimistic locking, domain events
6. EventBridge routing + SNS alerts + saved-search matching
7. CloudWatch metrics, logs, alarms
8. test/data fixtures + SeedDataLoader + fill functional-test gaps so the
   backend 75% functional gate passes across all flows
9. README: Mermaid architecture diagram, run instructions, sample curls,
   "differences from real AWS" section
10. React frontend + S3 website hosting + CORS + deploy script; Vitest/RTL
    unit tests (90%), Playwright functional tests with Istanbul coverage
    (75%), OTel Web SDK traces connected to backend traces
11. Telemetry-as-oracle functional test (single connected trace across the
    offer pipeline) + test/README.md endpoint/flow coverage table
12. (Bonus) infra/cfn: same infra as CloudFormation/CDK deployed to the
    emulator

Current phase: **1 — not started.**

## Working style

- This is a LEARNING project. In each phase, explain what each AWS service
  does, why it fits, and what differs on real AWS (cost, IAM, latency,
  limits).
- Run code and tests yourself against the emulator; read output and fix
  issues before handing back. A phase is done only when `make test` passes
  with all coverage gates green.
- When debugging async flows, read `telemetry/traces/` and
  `telemetry/logs/` first — the trace shows exactly where a message stalled.
- If the emulator can't do something (501/503, missing feature), say so plainly
  and propose a workaround — never fake it silently.
- Keep endpoints, credentials, and resource names out of code — config only.
- Never point any tool at real AWS from this repo. If `AWS_ENDPOINT_URL` is
  unset in a shell, commands go to real AWS — always verify before running
  AWS CLI commands.
