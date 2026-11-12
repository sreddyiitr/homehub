# HomeHub — Requirements

Status: **Draft v0.1** (derived from CLAUDE.md, 2026-11-11). Open questions
and verification items are in `decisions.md`.

## 1. Purpose & scope

HomeHub is a service for managing real estate listings and offers. Agents
publish listings with photos. Buyers search listings, save searches, and
submit offers. Offers are processed asynchronously. Buyers get alerts on
saved-search matches and when an offer's status changes.

**In scope:** backend API, async workers, React SPA, local AWS Emulator
infra, OpenTelemetry to local files, test tiers with coverage gates.
**Out of scope (v1):** real authentication/authorization (see OQ-1),
payments, email/SMS delivery (SNS → SQS only), multi-region, a production
AWS deployment (Phase 12 targets the emulator only).

### Actors
| Actor | ID pattern | Role value |
|---|---|---|
| Agent | `AGT-nnn` | `agent` |
| Buyer | `BUY-nnn` | `buyer` |
| System (workers, bootstrap, seeding) | — | `system` |

---

## 2. Functional requirements

### 2.1 Configuration & profiles (R-CFG)

- **R-CFG-01** THE SYSTEM SHALL support two Spring profiles: `local` (default) and `aws`.
  - .1 WHILE `local` is active, AWS clients SHALL use the endpoint from `AWS_ENDPOINT_URL` and region `us-west-2`.
  - .2 WHILE `aws` is active, no endpoint override SHALL be applied, and the default credential chain SHALL be used.
  - .3 WHILE `aws` is active, infra bootstrap and seeding SHALL be disabled and SHALL NOT be possible to enable by mistake. A startup check fails if `homehub.bootstrap-infra` or `homehub.seed-data` is true under `aws`.
- **R-CFG-02** Endpoints, credentials, and region SHALL come only from configuration or environment. A unit/ArchUnit test fails on any hardcoded `http(s)://` endpoint or `EMULATOR*` key in main sources.
- **R-CFG-03** All AWS resource names SHALL be defined in exactly one backend config class. Scripts and tests SHALL get names from the same source (mechanism: ⚠ OQ-6).
- **R-CFG-04** IF the local profile starts and `AWS_ENDPOINT_URL` is unset THEN the app SHALL fail fast with a clear message and SHALL NOT fall back to real AWS.
- **R-CFG-05** WHEN the app starts, it SHALL load the geocoding API key from Secrets Manager and feature flags from SSM Parameter Store. IF either is missing THEN startup fails (local) with the parameter or secret name in the message. The secret value is never logged.

### 2.2 Infra bootstrap (R-INF) — local profile only

- **R-INF-01** WHEN the app starts with `homehub.bootstrap-infra=true`, an `@Order(1)` ApplicationRunner SHALL create every resource in design.md §3 that doesn't already exist.
  - .1 Running it twice in a row SHALL succeed, and the second run SHALL create nothing (idempotent).
  - .2 After an emulator restart (all state lost), the next app start SHALL recreate everything.
  - .3 SQS DLQs SHALL use `maxReceiveCount=3`.
  - .4 Bootstrap SHALL run inside one span `bootstrap-infra`, with one child span per resource type.
- **R-INF-02** `make infra` SHALL run the same bootstrap code without starting the HTTP API.
- **R-INF-03** IF a bootstrap call returns HTTP 501 or 503 (an unimplemented emulator operation; 501 verified) THEN bootstrap SHALL fail loudly, naming the service and operation, and SHALL NOT continue as if it succeeded.
- **R-INF-04** `infra/iam/` SHALL contain a least-privilege policy JSON for the app role covering every action the code calls. (Nothing enforces it locally; it gets reviewed by hand.)

### 2.3 Seed data (R-SEED) — local profile only

- **R-SEED-01** WHEN `homehub.seed-data=true`, an `@Order(2)` runner SHALL load `test/data/*.json` into DynamoDB and upload `test/data/photos/` to `homehub-listing-photos`.
  - .1 IF the sentinel listing (`LST-0001`) already exists THEN seeding SHALL be skipped.
  - .2 Seed offers SHALL be written in their final states directly. Seeding SHALL NOT enqueue messages or emit domain events.
- **R-SEED-02** The fixtures SHALL contain ~5 agents, ~8 buyers, ~20 listings in 4–5 cities across price tiers, ~15 offers covering every offer status, and ~6 saved searches (some that match, some that don't).
- **R-SEED-03** The fixtures SHALL include these edge cases: a DRAFT listing; a SOLD listing; a listing with ≥2 competing open offers; a listing with no photos; offers above and below asking price; one buyer with offers on ≥3 listings.
- **R-SEED-04** A validation test SHALL assert that every offer→listing, offer→buyer, listing→agent, and savedSearch→buyer reference resolves. It SHALL also assert that every ID matches its pattern.
- **R-SEED-05** Fixtures are static and deterministic. Nothing is randomly generated at runtime.

### 2.4 Health (R-HLTH)

- **R-HLTH-01** `GET /actuator/health` SHALL report one indicator per AWS dependency (s3, dynamodb, sqs, sns, eventbridge, cloudwatch, logs, secretsmanager, ssm). It SHALL also report a `seedData` indicator with counts of staged listings and offers.
  - .1 Overall status SHALL be `UP` only after bootstrap and seeding have finished.
  - .2 The telemetry collector being down SHALL NOT make health `DOWN` (telemetry is best-effort).

### 2.5 Listings (R-LIST)

- **R-LIST-01** WHEN an agent sends `POST /listings` with a valid body, THE SYSTEM SHALL create a listing with status `DRAFT` and `version=1` owned by that agent, and return `201` with a `Location` header.
  - .1 IF the body is invalid (missing address/city/price, price ≤ 0, beds < 0) THEN return `400` problem+json with field errors.
  - .2 IF the caller is not an agent THEN return `403`.
- **R-LIST-02** `GET /listings/{id}` SHALL return the listing. Unknown id → `404`. DRAFT listings are visible only to the owning agent (⚠ OQ-1).
- **R-LIST-03** WHEN the owning agent sends `PATCH /listings/{id}` with `If-Match: <version>`, THE SYSTEM SHALL apply a partial update using a DynamoDB conditional write on `version`, and increment `version`.
  - .1 IF `If-Match` is missing THEN return `428`. IF it is stale, or the conditional write loses a race, THEN return `412` (design §4.1).
  - .2 IF the listing is `SOLD` THEN return `409` (terminal state).
  - .3 WHEN `price` decreases on an `ACTIVE` listing, THE SYSTEM SHALL emit `PriceChanged` with `oldPrice`/`newPrice`.
  - .4 IF a non-owner PATCHes THEN return `403`.
- **R-LIST-04** WHEN the owner sends `POST /listings/{id}/publish` for a `DRAFT` listing, THE SYSTEM SHALL move it to `ACTIVE` and emit `ListingPublished`.
  - .1 Publishing a listing that is not `DRAFT` → `409`.
  - .2 Publishing SHALL require price > 0, city, and address. It SHALL NOT require photos (the fixtures include an ACTIVE listing with no photos).
- **R-LIST-05** Listing status SHALL follow the state machine in design.md §5.1. Any other transition is rejected, and every transition emits a `listing.status.changed` span event with `from`/`to`.
- **R-LIST-06** How a listing becomes `SOLD` (endpoint or event): ⚠ OQ-3.

### 2.6 Search (R-SRCH)

- **R-SRCH-01** `GET /listings/search?city=&minPrice=&maxPrice=&minBeds=` SHALL return only `ACTIVE` and `UNDER_OFFER` listings (⚠ OQ-4) that match every filter given.
  - .1 `city` is required (it is the GSI partition key). If it is missing → `400`.
  - .2 The price range SHALL be applied in the GSI key condition, not by a filter.
  - .3 DRAFT and SOLD listings SHALL NEVER appear in results.
  - .4 Results SHALL be paginated (`limit` default 20, max 100; opaque `nextToken`).
  - .5 Results SHALL be sorted by price ascending.
- **R-SRCH-02** The search SHALL be a DynamoDB GSI Query and SHALL NOT be a Scan. (A unit test asserts that the repository never calls `scan` for search.)

### 2.7 Photos (R-PHOTO)

- **R-PHOTO-01** WHEN the owning agent sends `POST /listings/{id}/photos/upload-url` with `contentType` (jpeg/png/webp) and `contentLength` ≤ 10 MB, THE SYSTEM SHALL return a presigned PUT URL (expiry 5 min) for key `listings/{listingId}/original/{photoId}.{ext}`.
  - .1 Unsupported type or oversized → `400`.
- **R-PHOTO-02** WHEN an object lands under `listings/*/original/`, an S3 event notification SHALL deliver a message to `photo-processing-queue` 🔬 V-6.
- **R-PHOTO-03** WHEN the photo worker receives the event, it SHALL generate a thumbnail at `listings/{listingId}/thumbnails/{photoId}.jpg` (max 400px wide) and add the photo to the listing (optimistic lock, with retry).
  - .1 IF processing fails THEN the message SHALL be retried by SQS. After 3 receives it goes to the DLQ. Each failure increments `homehub.photo.processing.failures` and the CloudWatch `PhotoProcessingFailures` metric.
  - .2 A corrupt or non-image object SHALL end up in the DLQ without crashing the worker.
  - .3 Processing the same S3 event twice SHALL NOT duplicate the photo on the listing (idempotent by photoId).
- **R-PHOTO-04** `GET /listings/{id}/photos` SHALL return each photo with a presigned GET URL (expiry 15 min) for the original and, once processed, for the thumbnail. Photos still waiting for a thumbnail show `thumbnailStatus=PENDING`.
- **R-PHOTO-05** `homehub-listing-photos` SHALL have a bucket CORS policy that allows `PUT` and `GET` from the configured UI origin(s) 🔬 V-4.

### 2.8 Offers (R-OFFER)

- **R-OFFER-01** WHEN a buyer sends `POST /listings/{id}/offers` with `amount`, THE SYSTEM SHALL save the offer as `SUBMITTED`, enqueue a `SubmitOffer` command to `offer-processing-queue.fifo` (MessageGroupId=listingId), emit `OfferSubmitted`, and return `202` with the offer.
  - .1 The request SHALL return without waiting for validation.
  - .2 IF the caller is not a buyer THEN return `403`.
  - .3 Syntactic errors (missing amount, non-numeric) → `400` synchronously. Business validation happens asynchronously.
- **R-OFFER-02** WHEN the processor handles `SubmitOffer`, it SHALL validate that the listing is `ACTIVE`, `amount > 0`, and the buyer has no other open offer on that listing.
  - .1 Valid → status `UNDER_REVIEW` ⚠ OQ-2.
  - .2 Invalid → status `REJECTED` with `rejectionReason` (`LISTING_NOT_ACTIVE` | `INVALID_AMOUNT` | `DUPLICATE_OPEN_OFFER`). Emit `OfferRejected`. Record a span event `offer.rejected` with `reason` and set `homehub.outcome=rejected`. Span status stays **not** ERROR.
- **R-OFFER-03** WHEN the listing's agent sends `POST /offers/{id}/accept|reject|counter`, THE SYSTEM SHALL enqueue the matching command to the FIFO queue (same MessageGroupId=listingId) and return `202`.
  - .1 Only the listing's owning agent may act → otherwise `403`.
  - .2 Only offers in `UNDER_REVIEW` (or `COUNTERED` for reject) may be acted on → otherwise `409` synchronously, if the current state already shows the action is invalid.
  - .3 `counter` requires `counterAmount > 0`.
- **R-OFFER-04** WHEN the processor applies `AcceptOffer`, it SHALL set the offer to `ACCEPTED` and emit `OfferAccepted`. A rule then routes that event so that the listing moves to `UNDER_OFFER`, and every other open offer on the listing moves to `REJECTED` (`reason=COMPETING_OFFER_ACCEPTED`), each emitting `OfferRejected`.
  - .1 IF two accepts for different offers on the same listing race THEN exactly one SHALL succeed. FIFO per-listing ordering plus the conditional write guarantee this, and a functional test proves it.
- **R-OFFER-05** Every offer status change SHALL use a conditional write on the offer's `version`. On conflict, retry up to 3 times, recording an `optimistic-lock.retry` span event with `attempt` each time. After that, fail the message (so it eventually lands in the DLQ).
- **R-OFFER-06** Processing the same command twice SHALL produce the same end state and SHALL emit domain events only once (idempotent consumer, design §6.3).
- **R-OFFER-07** `GET /listings/{id}/offers` returns the listing's offers (owning agent: all of them; a buyer: only their own ⚠ OQ-1). Buyers list their own offers via ⚠ OQ-7.
- **R-OFFER-08** An offer on a `SOLD` or `DRAFT` listing SHALL be accepted at the HTTP layer (`202`) and then rejected asynchronously with `LISTING_NOT_ACTIVE`. This is the business-rejection path, not an error.

### 2.9 Domain events & routing (R-EVT)

- **R-EVT-01** THE SYSTEM SHALL publish these events to bus `homehub-events` with `source=homehub.api` (or `homehub.worker`): `ListingPublished`, `PriceChanged`, `ListingSold`, `OfferSubmitted`, `OfferAccepted`, `OfferRejected`, `OfferCountered`. Schemas are in design.md §6.
- **R-EVT-02** EventBridge rules SHALL route:
  - `ListingPublished`, `PriceChanged` → `search-match-queue`
  - `OfferAccepted` → `offer-processing-queue.fifo` (close competing offers, mark the listing UNDER_OFFER) 🔬 V-8
  - all `Offer*` outcome events (Accepted/Rejected/Countered) → SNS `homehub-buyer-alerts`
- **R-EVT-03** Every domain event SHALL also be written as an OTel log record with `event.name` (e.g. `homehub.offer.accepted`) and entity-id attributes, plus a span event on the active span.
- **R-EVT-04** Each event SHALL carry W3C `traceparent` (and the initiating `enduser.id`) so consumers can continue the trace (design §8.3).

### 2.10 Saved searches & alerts (R-ALERT)

- **R-ALERT-01** `POST /buyers/{id}/saved-searches` SHALL create a saved search (`city` required; optional `minPrice`, `maxPrice`, `minBeds`; `notify` default true). Only that buyer may create it.
- **R-ALERT-02** `GET /buyers/{id}/saved-searches` returns that buyer's saved searches (caller must be that buyer).
- **R-ALERT-03** WHEN a `ListingPublished` or `PriceChanged` (decrease) message arrives on `search-match-queue`, the matcher SHALL find every saved search with `notify=true` whose criteria match the listing. For each match, it SHALL publish a `SavedSearchMatch` alert to SNS with message attribute `buyerId`.
  - .1 The span `match-saved-searches` SHALL record a `search.matched` event with `count`.
  - .2 A price change that doesn't drop the price into a buyer's range for the first time SHALL NOT re-alert that buyer (⚠ OQ-5).
- **R-ALERT-04** WHEN an offer's status changes to ACCEPTED/REJECTED/COUNTERED, the buyer SHALL receive an SNS alert with `buyerId` and `offerId` attributes.
- **R-ALERT-05** For test assertions, an SQS queue SHALL be subscribed to `homehub-buyer-alerts` (raw message delivery) 🔬 V-7.

### 2.11 CloudWatch (R-CW)

- **R-CW-01** THE SYSTEM SHALL publish metrics in namespace `HomeHub`: `ListingsPublished`, `OffersSubmitted`, `OffersAccepted`, `PhotoProcessingFailures`, `OfferToListRatio`.
- **R-CW-02** Alarms SHALL exist on `PhotoProcessingFailures ≥ 1 in 5 min` and on `ApproximateNumberOfMessagesVisible ≥ 1` for each DLQ.
- **R-CW-03** Structured JSON application logs SHALL be shipped to log group `/homehub/api` without blocking request threads.

### 2.12 Frontend (R-FE)

- **R-FE-01** Listings grid: shows ACTIVE listings with thumbnails (presigned GET) and search filters (city, price range, min beds).
- **R-FE-02** Listing detail: gallery, offers (as allowed for the viewer), and a submit-offer form.
- **R-FE-03** Buyer view: my offers with polled status (TanStack Query `refetchInterval`), and saved searches (list + create).
- **R-FE-04** Agent view: create/edit/publish listings; upload photos in the browser via presigned PUT (thumbnail appears via polling); accept/reject/counter offers.
  - .1 After submitting an offer, the UI SHALL show the status change without a manual reload.
- **R-FE-05** API types SHALL be generated from the backend OpenAPI spec as a build step. The frontend SHALL NOT import backend code.
- **R-FE-06** The SPA SHALL be hosted on S3 bucket `homehub-web` with website hosting, error document `index.html` for SPA routes, and correct `Content-Type` per file 🔬 V-4.
- **R-FE-07** The browser SHALL emit OTel traces to the collector at `:4318`: one root span per USER ACTION (a click on a `data-action` element, or `open-app`), with the API fetches it triggers as children. Fetch calls carry `traceparent`, so the API server span joins the same trace (D-11).
  - .1 Presigned S3 URLs SHALL be redacted from span attributes.
  - .2 With no collector URL configured, tracing SHALL be off and the app unaffected.

---

## 3. Non-functional requirements

### 3.1 Observability (R-OBS)
- **R-OBS-01** Backend telemetry SHALL go through the OTel Java agent, configured only by env vars. App code depends only on `opentelemetry-api` and `opentelemetry-instrumentation-annotations`. (An ArchUnit rule forbids `io.opentelemetry.sdk..` in main.)
- **R-OBS-02** Every API server span SHALL carry `enduser.id`, `homehub.actor.role`, `client.address`, `user_agent.original`. These are set once in a filter.
- **R-OBS-03** Async consumers SHALL set `homehub.actor.role=system` and carry the original `enduser.id` from message attributes.
- **R-OBS-04** Entity ids touched SHALL be span attributes (`homehub.listing.id`, …). Span names SHALL be low-cardinality and never contain ids (unit test with regex `[A-Z]{3}-\d+`).
- **R-OBS-05** The critical-juncture spans and decision events listed in design.md §8.2 SHALL exist, and unit tests SHALL assert them via `opentelemetry-sdk-testing`.
- **R-OBS-06** Errors: `recordException` + `setStatus(ERROR, short msg)` on the span where the error happened. The error is logged once, where it is handled. Business rejections are not errors.
- **R-OBS-07** DLQ arrivals SHALL emit a `message.dead_lettered` event and increment `homehub.messages.dead_lettered` (mechanism: design §7.3).
- **R-OBS-08** One offer submission SHALL appear as ONE connected trace: HTTP → SQS send → SQS receive → processor → EventBridge put → (routed consumer). Span links are used for batch consumers.
- **R-OBS-09** Business metrics are recorded with the OTel Meter: `homehub.listings.published`, `homehub.offers.submitted`, `homehub.offers.accepted`, `homehub.photo.processing.failures`, `homehub.offer.to_list_ratio` (histogram), `homehub.queue.processing.duration` (histogram, attribute `queue`).
- **R-OBS-10** If the collector is down, the app SHALL keep serving requests with no added latency beyond exporter timeouts (asserted in a functional test with the collector stopped, or documented as manual — ⚠ OQ-9).
- **R-OBS-11** One request SHALL show up in all three files: `telemetry/traces/traces.jsonl`, `metrics/metrics.jsonl`, `logs/logs.jsonl` (Phase 2 proof).

### 3.2 Privacy (R-PII)
- **R-PII-01** Telemetry and logs SHALL NEVER contain emails, names, phone numbers, buyer addresses, credentials, presigned URLs, or full request/response bodies.
- **R-PII-02** A unit test SHALL scan spans and logs captured by an InMemory exporter during representative flows for: email regex, `X-Amz-Signature`, `X-Amz-Credential`, `EMULATOR[A-Z0-9]+`, and phone patterns. The test fails on any hit.

### 3.3 Architecture (R-ARCH)
- **R-ARCH-01** Layering: controllers → services → repositories/adapters. No AWS SDK types in controllers or the domain package (ArchUnit).
- **R-ARCH-02** Frontend and backend communicate only over HTTP.

### 3.4 Testing & coverage (R-TEST)
- **R-TEST-01** Backend unit tests (`*Test.java`, Surefire): no emulator, no network. JaCoCo `jacoco-ut.exec` gate ≥ 90% line AND branch.
- **R-TEST-02** Backend functional tests (`*IT.java`, Failsafe): full app on `local` against the emulator, with the OTel agent attached. REST Assured + Awaitility (no `Thread.sleep`). JaCoCo `jacoco-it.exec` gate ≥ 75% line.
- **R-TEST-03** Frontend unit tests (Vitest + RTL + MSW): thresholds lines/branches/functions/statements = 90.
- **R-TEST-04** Frontend functional tests (Playwright vs. the S3-hosted, Istanbul-instrumented build): `nyc check-coverage --lines 75`. The instrumented build is never deployed for normal use.
- **R-TEST-05** Coverage exclusions SHALL be limited to: generated OpenAPI types/clients, `*Application.java`, pure config/properties classes, logic-free records/DTOs, `main.tsx`. Each one is listed explicitly.
- **R-TEST-06** Every REST endpoint and every frontend user flow SHALL have ≥1 functional test, recorded in `test/README.md`.
- **R-TEST-07** Telemetry oracle test: submit an offer, then assert from `telemetry/traces/` that one trace contains the HTTP, SQS send, SQS receive, and processor spans, and that `telemetry/logs/` holds an `OfferAccepted`/`OfferRejected` event record with the same `trace_id`.
- **R-TEST-08** Gates are never lowered or skipped, and exclusions are never widened, just to get a build to pass. Gates are enforced from Phase 3 onward.

### 3.5 Operability (R-OPS)
- **R-OPS-01** `run-local.sh` / `make run` SHALL: check that the collector image exists; start the emulator (telemetry off) and the collector if they aren't running; reuse `homehub-otel` only if it mounts THIS repo's `telemetry/`; fetch the current EMULATOR* credentials and export them; download the pinned OTel agent if it's missing; start the app; print the URLs.
- **R-OPS-02** Every script that calls the AWS CLI SHALL first check that `AWS_ENDPOINT_URL` is set and points to localhost. Otherwise it aborts.
- **R-OPS-03** The Makefile targets listed in CLAUDE.md "Commands" SHALL exist and behave as described.
- **R-OPS-04** An emulator 501/503 is never stubbed around silently. It gets reported (operation name) together with a proposed workaround.
