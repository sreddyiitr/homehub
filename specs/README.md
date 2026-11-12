# HomeHub Specs — how spec-driven development works here

The files in `specs/` are the **source of truth for behavior**. CLAUDE.md holds
the project rules and conventions. The specs hold what the system must do,
written so that each item can be tested.

| File | Answers | Changes when |
|---|---|---|
| `requirements.md` | **What** must be true: numbered requirements with acceptance criteria | Scope or behavior changes |
| `design.md` | **How**: data model, API contracts, events, queues, telemetry | Implementation approach changes |
| `tasks.md` | **In what order**: phase-by-phase tasks, each linked to requirement IDs | Work is planned or completed |
| `decisions.md` | **Why**: decisions made and questions still open | A question is answered or a decision is reversed |

## Workflow (every phase, every feature)

1. **Spec first.** Before writing code, check that the behavior is in
   `requirements.md`. If it isn't, add it (or ask) first. New requirement IDs
   are never reused, and deleted ones stay struck through.
2. **Design second.** Update `design.md` for any new table, attribute,
   endpoint, message, event, or span.
3. **Tests from acceptance criteria.** Each acceptance criterion (AC) becomes
   at least one test. Put the AC id in the test's display name:
   `@DisplayName("R-OFFER-03.2 rejects offer on SOLD listing")` /
   `it('R-FE-04.1 shows status change', ...)`.
4. **Code until the tests pass.** Then tick the task in `tasks.md`.
5. **Traceability.** `test/README.md` maps each endpoint/flow → test name
   (required by CLAUDE.md). It also gets a requirement-ID column, so a
   requirement with no test shows up as a gap.
6. **STOP at phase end** for review (CLAUDE.md rule). A phase is done only
   when `make test` passes with every gate that applies to that phase green.

## Conventions

- Requirement IDs: `R-<AREA>-<NN>`. Acceptance criteria: `R-<AREA>-<NN>.<n>`.
  (The `R-` prefix keeps them distinct from fixture IDs like `LST-0001`.)
- Requirements use EARS phrasing: *WHEN <trigger> THE SYSTEM SHALL <response>*,
  *WHILE <state> …*, *IF <unwanted condition> THEN …*.
- **MUST / SHOULD / MAY** follow RFC 2119.
- An item marked `⚠ OQ-n` depends on an open question in `decisions.md`.
  Don't implement past it until the question is answered.
- An item marked `🔬 V-n` depends on emulator behavior that Phase 1 has to
  verify (list in `decisions.md`).
