# Contributing to cloud-itonami-isic-7990

Contributions should preserve the actor's scope: back-office ticketing/
event-reservation-agency coordination only, with CRITICAL exclusions of
directly finalizing a payment-dispute resolution and directly finalizing an
access-eligibility override (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Directly finalize a payment-dispute resolution (chargebacks, refund disputes).
- Directly finalize an access-eligibility override (ticket/reservation redemption or event-access eligibility).

Contributions that cross these boundaries will be rejected.
