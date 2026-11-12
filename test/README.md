# Test traceability

Every REST endpoint and user flow needs at least one functional test
(R-TEST-06). A missing row is a bug, even when the coverage gate passes.

| Endpoint / flow | Requirement | Functional test | Unit tests |
|---|---|---|---|
| `GET /listings/search` | R-SRCH-01, R-SRCH-02 | `ListingsApiIT.search*` | `ListingServiceTest`, `DynamoDbListingRepositoryTest`, `ListingControllerTest` |
| `GET /listings/{id}` | R-LIST-02 | `ListingsApiIT.getListing`, `getSoldListingIsVisible`, `getDraftOrUnknown` | `ListingServiceTest`, `ListingControllerTest` |
| `GET /actuator/health/readiness` | R-HLTH-01 (partial) | `ListingsApiIT.readinessIsUpAfterBootstrapAndSeed` | — |
| Bootstrap: Listings table + GSIs | R-INF-01 | implicit (every IT) | `ListingsTableBootstrapTest`, `InfraBootstrapRunnerTest` |
| Seed: listings fixtures | R-SEED-01 | implicit (fixture ids asserted) | `SeedDataLoaderTest` |
| UI: listings grid + filters + paging | R-FE-01 | _Playwright — Phase 10_ | `ListingsPage.test.tsx` |
| UI: listing detail | R-FE-02 (partial) | _Playwright — Phase 10_ | `ListingDetailPage.test.tsx` |
