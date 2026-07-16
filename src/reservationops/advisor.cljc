(ns reservationops.advisor
  "ReservationOpsAdvisor -- the *contained intelligence node* for the
  ISIC-7990 (Other reservation service and related activities --
  ticketing agencies, event-reservation services not elsewhere
  classified as travel-agency/tour-operator activities)
  operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: booking/ticket-issuance record logging, inventory/
  seat-allocation scheduling, vendor/venue settlement coordination, and
  payment-dispute/fraud/access-eligibility-concern flagging. CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by
  `reservationops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a direct finalization of a payment-dispute
  resolution, nor a direct finalization of an access-eligibility
  override -- those are permanently out of scope for this actor, not
  merely un-implemented (Wave 4 person-facing-service safety guardrail,
  ADR-2607152500). `reservationops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :reservation-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-reservation-record
  "Draft a booking/ticket-issuance log entry (reservation created /
  amended / ticket issued). Pure logging of observed reservation state
  -- never a payment-dispute or access-eligibility decision."
  [_db {:keys [reservation-id patch]}]
  {:op         :log-reservation-record
   :reservation-id reservation-id
   :summary    (str reservation-id " の予約/発券記録を記録: " (pr-str (keys patch)))
   :rationale  "予約作成/変更またはチケット発券状況の記録のみ。決済紛争やアクセス資格の判断は含まない。"
   :cites      [reservation-id]
   :effect     :propose
   :value      (merge {:reservation-id reservation-id} patch)
   :confidence 0.94})

(defn- propose-allocation-operation
  "Draft an inventory/seat-allocation scheduling PROPOSAL only (never a
  direct seat-lock or inventory-release actuation)."
  [_db {:keys [reservation-id patch]}]
  {:op         :schedule-allocation-operation
   :reservation-id reservation-id
   :summary    (str reservation-id " に関連する在庫/座席割当スケジュール提案: " (pr-str (keys patch)))
   :rationale  "座席/在庫割当の日程調整の提案のみ。割当確定は人間の予約管理者が判断する。"
   :cites      [reservation-id]
   :effect     :propose
   :value      (merge {:reservation-id reservation-id} patch)
   :confidence 0.89})

(defn- propose-vendor-settlement
  "Draft a vendor/venue settlement coordination proposal (never a
  direct fund transfer or settlement finalization)."
  [_db {:keys [reservation-id patch]}]
  {:op         :coordinate-vendor-settlement
   :reservation-id reservation-id
   :summary    (str reservation-id " に関連するベンダー/会場精算調整: " (pr-str (keys patch)))
   :rationale  "ベンダー/会場との精算調整の提案のみ。精算確定は人間の予約管理者が判断する。"
   :cites      [reservation-id]
   :effect     :propose
   :value      (merge {:reservation-id reservation-id} patch)
   :confidence 0.87})

(defn- propose-transaction-concern
  "Surface a payment-dispute/fraud/access-eligibility concern for
  HUMAN triage. This op ALWAYS escalates in `reservationops.governor`
  -- never auto-committed at any phase -- regardless of how confident
  the advisor is that the concern is real, and it never itself
  finalizes a payment-dispute resolution or an access-eligibility
  override."
  [_db {:keys [reservation-id patch]}]
  {:op         :flag-transaction-concern
   :reservation-id reservation-id
   :summary    (str reservation-id " の取引懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "決済/不正/アクセス資格に関する懸念の観察事実の報告のみ。常に人間の確認・対応が必要。"
   :cites      [reservation-id]
   :effect     :propose
   :value      (merge {:reservation-id reservation-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-reservation-record (propose-reservation-record _db request)
                   :schedule-allocation-operation (propose-allocation-operation _db request)
                   :coordinate-vendor-settlement (propose-vendor-settlement _db request)
                   :flag-transaction-concern (propose-transaction-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalize the payment dispute resolution and grant the access eligibility override directly")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :reservation-id (:reservation-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
