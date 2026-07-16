(ns reservationops.store
  "SSoT for the ISIC-7990 (Other reservation service and related
  activities -- ticketing agencies, event-reservation services not
  elsewhere classified as travel-agency/tour-operator activities)
  OPERATIONS-COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a ticketing /
  event-reservation agency: booking/ticket-issuance data logging,
  inventory/seat-allocation scheduling, vendor/venue settlement
  coordination, and payment-dispute/fraud/access-eligibility concern
  flagging. It NEVER directly finalizes a payment-dispute resolution or
  an access-eligibility override -- see `reservationops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable
  block, per this fleet's Wave 4 person-facing-service safety guardrail
  (ADR-2607152500).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `reservations` directory keyed by `:reservation-id`
  STRING (never a keyword -- consistent keying from the start, avoiding
  the silent-miss bug that plagued an earlier shepherd attempt). Each
  entry covers either a customer booking/ticket reservation or a
  vendor/venue settlement contract -- both share the same
  registered/verified lifecycle before any coordination proposal may
  touch them.

  A registered/verified reservation-or-vendor-contract record must
  exist before ANY proposal for it may ever commit or escalate --
  `reservationops.governor`'s `reservation-unverified-violations`
  re-derives this from the record's own `:registered?`/`:verified?`
  fields, never from a proposal's self-report, the SAME 'ground truth,
  not self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which reservation a proposal targeted,
  which operation, on what basis, committed/held/escalated and approved
  by whom is always a query over an immutable log.")

(defprotocol Store
  (reservation [s reservation-id] "Registered reservation/vendor-contract
    record, or nil. Map: {:reservation-id .. :name .. :kind ..
    :registered? bool :verified? bool}.")
  (all-reservations [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-reservations [s reservations] "replace/seed the reservation directory (map reservation-id->reservation)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained reservation directory covering both the
  happy path and the governor's own hard checks, so the actor + tests
  run offline."
  []
  {:reservations
   {"res-1" {:reservation-id "res-1" :name "General-admission concert ticket block, venue A, 2026-08-01"
             :kind :customer-booking :registered? true :verified? true}
    "res-2" {:reservation-id "res-2" :name "Venue B vendor/settlement contract, quarterly reconciliation"
             :kind :vendor-contract :registered? true :verified? true}
    "res-3" {:reservation-id "res-3" :name "Reserved-seating theatre block, awaiting box-office verification"
             :kind :customer-booking :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (reservation [_ reservation-id] (get-in @a [:reservations reservation-id]))
  (all-reservations [_] (sort-by :reservation-id (vals (:reservations @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-reservations [s reservations] (when (seq reservations) (swap! a assoc :reservations reservations)) s))

(defn seed-db
  "A MemStore seeded with the demo reservation directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `reservations` map
  (reservation-id string -> reservation map) -- the primary test/dev
  entry point. `reservations` may be empty (an unregistered-everywhere
  store)."
  [reservations]
  (->MemStore (atom {:reservations (or reservations {}) :ledger [] :coordination-log []})))
