# cloud-itonami-isic-7990

**Other reservation service and related activities** — ISIC Rev.4 class 7990.

Ticketing agencies and event-reservation services not elsewhere classified as travel-agency/tour-operator activities (see ISIC 7911/7912). A coordination-only actor for ticketing/reservation-agency back-office operations, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-reservation-record, schedule-allocation-operation, coordinate-vendor-settlement, flag-transaction-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Reservation verified** — target reservation/vendor-contract record must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — directly finalizing a payment-dispute resolution and directly finalizing an access-eligibility override are permanently blocked. This actor never has the authority to directly finalize a consumer-payment dispute or an event-access-eligibility decision — see CRITICAL below.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: reservation-record logging only (approval-gated)
  - Phase 2: + allocation-operation scheduling, vendor-settlement coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (transaction concerns always escalate; over-threshold vendor-settlement proposals always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL — scope

This is a consumer-facing ticketing/event-reservation operations-coordination actor, **not** a payment-dispute or access-eligibility authority. It coordinates back-office logistics only. It **NEVER**:

- Directly finalizes a payment-dispute resolution (chargebacks, refund disputes).
- Directly finalizes an access-eligibility override (who is or is not permitted to redeem a ticket/reservation or gain event access).

`flag-transaction-concern` only ever *surfaces* an observed concern (payment dispute, suspected fraud pattern, access-eligibility question) for a human to triage — it is never a member of any phase's `:auto` set, at any phase, and it is always escalated to human sign-off. A `coordinate-vendor-settlement` proposal whose estimated settlement amount exceeds the governor's value threshold ($5,000) is likewise always escalated to a human, regardless of confidence.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/reservationops/governor_test.clj` — unit tests of governor hard checks, scope exclusion, and the fleet's known self-tripping-bug regression (`default-mock-advisor-proposals-never-self-trip-scope-exclusion`)
- `test/reservationops/advisor_test.clj` — advisor proposal shape and consistency
- `test/reservationops/phase_test.clj` — rollout phase logic
- `test/reservationops/governor_contract_test.clj` — full graph integration, audit trail
- `test/reservationops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `reservationops.store` — SSoT (MemStore, String-keyed reservation directory, append-only ledger)
- `reservationops.advisor` — contained intelligence node (mock + real-LLM seam)
- `reservationops.governor` — independent compliance layer
- `reservationops.phase` — staged rollout (0→3)
- `reservationops.operation` — langgraph-clj StateGraph
- `reservationops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and the paired ADR for cloud-itonami-isic-7990 for design decisions.
