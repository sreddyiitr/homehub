# HomeHub — Tasks

Each phase ends with a **STOP for review**. A phase is done when its exit
criteria hold and `make test` is green for the gates that apply (gates are
enforced from Phase 3 onward). Requirement IDs are in brackets.

Legend: `[ ]` todo · `[x]` done · `[~]` blocked (give the reason)

---

## Phase 0 — Walking-skeleton slice (owner request, 2026-11-11)
A thin end-to-end slice that proves the toolchain before the full phases.
Scope is deliberately narrow; the phases below then flesh it out.
- [x] Own emulator container `homehub-emulator` (telemetry off), creds exported by `run-local.sh` [R-OPS-01 partial]
- [x] Backend: profiles + startup guards, `HomeHubResources`, DynamoDB client with emulator CA [R-CFG-01/02/04]
- [x] Bootstrap: `Listings` table + `city-price-index` (sparse) + `agent-index`, idempotent, waits for ACTIVE [R-INF-01 partial]
- [x] Seed: `test/data/listings.json` (8 listings incl. DRAFT, SOLD, UNDER_OFFER), sentinel `LST-0001` [R-SEED-01 partial]
- [x] `GET /listings/search` (city required, price range on the key, pagination) + `GET /listings/{id}` [R-SRCH-01, R-SRCH-02, R-LIST-02 partial]
- [x] Unit tests + one functional IT against the emulator
- [x] Frontend: Vite+React+TS, Router, TanStack Query; grid with filters + detail page; types generated from OpenAPI [R-FE-01 partial, R-FE-05]
- [x] Vitest + RTL + MSW tests for the grid

**Done 2026-11-11:** 55 backend unit + 12 functional ITs (emulator), 22 frontend tests
(100% lines / 96.6% branches), `npm run build` clean.

Deferred to Phase 2 (not in the slice): OTel agent/collector wiring, JaCoCo
gates, ArchUnit, health indicators, the identity header (read-only endpoints
don't need it). DRAFT visibility for owners waits for the identity header,
so the slice returns 404 for DRAFT to everyone.

## Phase 1 — Emulator research
- [ ] Read the emulator docs and the launch notes; pull the image; run it with telemetry off
- [ ] Answer V-1 … V-14 in `decisions.md` with the commands used as evidence
- [ ] Prove the credential-fetch mechanism works in a script (V-3) [R-OPS-01]
- [ ] Smoke-test each service with the AWS CLI (endpoint check first) [R-OPS-02]
- [ ] Replace the TO VERIFY list in CLAUDE.md with the answers
- [ ] Owner answers OQ-1 … OQ-14 (or accepts the recommendations)

**Exit:** every V-* filled in, and every 503 reported with a proposed workaround.

## Phase 2 — Skeleton, telemetry, test tooling
- [ ] Maven project (Java 21, Spring Boot 3.x), packages per design §1.1
- [ ] Profiles `local`/`aws` + startup guards [R-CFG-01, R-CFG-04]
- [ ] `HomeHubResources` constants [R-CFG-03]; AWS client config from properties [R-CFG-02]
- [ ] Bootstrap runner skeleton (`@Order(1)`), idempotent, with a span; one bucket + one table for now [R-INF-01, R-INF-03]
- [ ] Prove connectivity: one S3 call + one DynamoDB call
- [ ] Health indicators per dependency [R-HLTH-01]
- [~] `run-local.sh` + Makefile `run`/`down`/`infra`/`telemetry-clean` [R-OPS-01, R-OPS-03] — run/down/telemetry/telemetry-clean done; `infra` pending
- [x] Collector container start/reuse with the mount check; OTel agent download (pinned) + env [R-OBS-01]
- [x] ActorFilter (who-invoked) [R-OBS-02] — header parsed + recorded; enforcement (401) with write endpoints
- [ ] PII-scan unit test harness [R-PII-02]
- [x] Prove one request appears in traces/metrics/logs jsonl [R-OBS-11]
- [ ] Surefire/Failsafe split, JaCoCo UT + IT executions and check rules, IT app started with both agents (design §9) [R-TEST-01, R-TEST-02]
- [ ] ArchUnit rules [R-ARCH-01, R-OBS-01]
- [ ] Endpoint/key hardcoding guard test [R-CFG-02]

**Exit:** `make run` → health UP; a curl produces telemetry in all 3 files; the gates are wired up (not yet enforced).

## Phase 3 — Listings CRUD + search GSI
- [ ] Full `Listings` table + GSIs in bootstrap [design §3.1]
- [ ] Domain `Listing`, `ListingStateMachine` + unit tests [R-LIST-05]
- [ ] POST/GET/PATCH/publish endpoints, problem+json, ETag/If-Match [R-LIST-01…04]
- [ ] Sparse `searchCity` maintenance + search endpoint with pagination [R-SRCH-01, R-SRCH-02]
- [ ] `transition-listing-status` span + event [R-OBS-05]
- [ ] ITs for each endpoint; update `test/README.md` [R-TEST-06]
- [ ] Least-privilege IAM for the actions used so far [R-INF-04]

**Exit:** both backend gates pass (≥90 UT, ≥75 IT).

## Phase 4 — Photos
- [ ] Bucket CORS + S3→SQS notification + queue/DLQ in bootstrap [R-PHOTO-05, R-PHOTO-02, R-INF-01.3]
- [ ] Upload-URL endpoint (validation, presign, optional signed traceparent metadata) [R-PHOTO-01, design §8.3]
- [ ] PhotoWorker: thumbnail, idempotent add with lock retry, failure → DLQ [R-PHOTO-03]
- [ ] Dead-letter observation [R-OBS-07]
- [ ] GET photos with presigned GETs [R-PHOTO-04]
- [ ] ITs: happy path, corrupt image → DLQ, duplicate event; PII test covers presigned URLs [R-PII-01]

## Phase 5 — Offer workflow
- [ ] `Offers` table + GSIs; FIFO queue + FIFO DLQ [design §3.1, §3.3]
- [ ] `OfferStateMachine` exhaustive unit tests [design §5.2]
- [ ] Submit endpoint (202) + command enqueue [R-OFFER-01]
- [ ] Processor: validate, transition with lock + retry, idempotent consumer [R-OFFER-02, R-OFFER-05, R-OFFER-06]
- [ ] Accept/reject/counter endpoints → commands [R-OFFER-03]
- [ ] Domain event publishing + OTel log records [R-EVT-01, R-EVT-03, R-EVT-04]
- [ ] ITs: valid → UNDER_REVIEW; SOLD listing → REJECTED; duplicate open offer; racing accepts [R-OFFER-04.1, R-OFFER-08]
- [ ] Trace continuity HTTP → SQS → processor [R-OBS-08]

## Phase 6 — EventBridge routing, SNS alerts, saved searches
- [ ] Bus + rules + targets; SNS topic + test-queue subscription [R-EVT-02, R-ALERT-05]
- [ ] OfferAccepted → CloseCompetingOffers + listing UNDER_OFFER [R-OFFER-04]
- [ ] `SavedSearches` table + endpoints [R-ALERT-01, R-ALERT-02]
- [ ] SearchMatchWorker + de-dup [R-ALERT-03]
- [ ] Offer outcome alerts [R-ALERT-04]
- [ ] EventBridge trace propagation via `detail.meta.traceparent` [design §8.3]

## Phase 7 — CloudWatch
- [ ] PutMetricData for business metrics + OTel Meter equivalents [R-CW-01, R-OBS-09]
- [ ] Alarms [R-CW-02]
- [ ] Async CW Logs appender, its own spans excluded from tracing [R-CW-03]

## Phase 8 — Fixtures + seeding + IT gap fill
- [ ] `test/data/*.json` + photos meeting R-SEED-02/03
- [ ] Referential-integrity test [R-SEED-04]
- [ ] SeedDataLoader with sentinel + `seedData` health [R-SEED-01, R-HLTH-01]
- [ ] `make seed`; fill IT gaps until the backend 75% gate passes across all flows

## Phase 9 — README
- [ ] Mermaid architecture diagram, run instructions, sample curls
- [ ] "Differences from real AWS" (IAM not enforced, no throttling, in-memory state, fidelity, collector → CloudWatch/X-Ray on AWS)

## Phase 10 — Frontend
- [ ] Vite + React + TS + Router + TanStack Query; OpenAPI type generation [R-FE-05]
- [ ] Pages: grid, detail, buyer, agent [R-FE-01…04]
- [ ] Website hosting + `deploy-frontend.sh` + Content-Type handling [R-FE-06]
- [ ] Spring CORS for the UI origin [design §2]
- [x] OTel Web SDK, traceparent on fetch [R-FE-07] — user-action spans (D-11); verified one trace web→api→DynamoDB in headless Chromium (done early, 2026-11-11)
- [ ] Vitest/RTL/MSW at 90% [R-TEST-03]
- [ ] Playwright smoke vs. the S3-hosted instrumented build, nyc 75% [R-TEST-04]

## Phase 11 — Telemetry oracle + flow table
- [ ] Oracle IT (single connected trace + matching event log) [R-TEST-07]
- [ ] `test/README.md` complete endpoint/flow → test → requirement table [R-TEST-06]

## Phase 12 — (Bonus) infra/cfn
- [ ] CloudFormation/CDK for the same infra, deployed to the emulator; diff against bootstrap output
