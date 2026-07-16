(ns reservationops.governor-test
  "Pure unit tests of `reservationops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [reservationops.governor :as gov]
            [reservationops.advisor :as advisor]
            [reservationops.store :as store]))

(def res-1 {:reservation-id "res-1" :name "GA concert ticket block" :kind :customer-booking
            :registered? true :verified? true})
(def res-3 {:reservation-id "res-3" :name "Theatre block, awaiting verification" :kind :customer-booking
            :registered? true :verified? false})

(defn- clean-proposal [op reservation-id]
  {:op op :reservation-id reservation-id :summary "s" :rationale "routine reservation operations coordination"
   :cites [reservation-id] :effect :propose :value {} :confidence 0.85})

(deftest reservation-unregistered-is-hard
  (testing "no reservation record at all -> HARD hold"
    (let [s (store/mem-store {"res-1" res-1})
          verdict (gov/check {} nil (clean-proposal :log-reservation-record "unknown-res") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:reservation-unverified} (map :rule (:violations verdict)))))))

(deftest reservation-unverified-is-hard
  (testing "reservation registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"res-3" res-3})
          verdict (gov/check {} nil (clean-proposal :log-reservation-record "res-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:reservation-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"res-1" res-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-allocation-operation "res-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"res-1" res-1})
          verdict (gov/check {} nil (clean-proposal :issue-full-refund "res-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest payment-dispute-finalization-is-hard-and-permanent
  (testing "a proposal that claims to directly finalize a payment-dispute resolution is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"res-1" res-1})
          poisoned (assoc (clean-proposal :flag-transaction-concern "res-1")
                          :rationale "finalize the payment dispute resolution in the customer's favor immediately"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest chargeback-determination-content-is-hard
  (testing "a proposal that claims to issue a chargeback determination is HARD-blocked, same as payment-dispute finalization"
    (let [s (store/mem-store {"res-1" res-1})
          poisoned (assoc (clean-proposal :flag-transaction-concern "res-1")
                          :rationale "issue a chargeback determination in favor of the merchant for res-1"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest access-eligibility-override-content-is-hard
  (testing "a proposal that claims to grant an access-eligibility override is HARD-blocked"
    (let [s (store/mem-store {"res-1" res-1})
          poisoned (assoc (clean-proposal :flag-transaction-concern "res-1")
                          :summary "grant the access eligibility override so the customer can enter")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest override-access-eligibility-decision-content-is-hard
  (testing "a proposal that claims to override an access-eligibility decision is HARD-blocked"
    (let [s (store/mem-store {"res-1" res-1})
          poisoned (assoc (clean-proposal :schedule-allocation-operation "res-1")
                          :value {:decision "override the access eligibility decision for this ticket block"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-transaction-concern-is-not-scope-excluded
  (testing "flagging observed payment-dispute/fraud/access-eligibility concerns (using the bare nouns 'payment', 'dispute', 'eligibility', 'access' as raw observation, never an executed finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"res-1" res-1})
          concern (assoc (clean-proposal :flag-transaction-concern "res-1")
                         :value {:concern "customer disputes a duplicate payment charge for res-1; also flagging an access eligibility question about a resold ticket for human review"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (payment dispute, access eligibility question) is exactly what this op exists to surface"))))

(deftest transaction-concern-always-escalates-even-when-otherwise-clean
  (testing ":flag-transaction-concern is always high-stakes/escalate, regardless of confidence"
    (let [s (store/mem-store {"res-1" res-1})
          concern (assoc (clean-proposal :flag-transaction-concern "res-1") :confidence 0.99)
          verdict (gov/check {} nil concern s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-value-vendor-settlement-always-escalates
  (testing "a coordinate-vendor-settlement proposal above the value threshold escalates even when governor-clean and high confidence"
    (let [s (store/mem-store {"res-1" res-1})
          expensive (assoc (clean-proposal :coordinate-vendor-settlement "res-1")
                           :value {:estimated-amount 9000} :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-value-vendor-settlement-does-not-force-escalation
  (testing "a coordinate-vendor-settlement proposal under the value threshold is not forced to escalate on value grounds alone"
    (let [s (store/mem-store {"res-1" res-1})
          routine (assoc (clean-proposal :coordinate-vendor-settlement "res-1")
                        :value {:estimated-amount 1200} :confidence 0.9)
          verdict (gov/check {} nil routine s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor escalates any otherwise-clean proposal"
    (let [s (store/mem-store {"res-1" res-1})
          uncertain (assoc (clean-proposal :log-reservation-record "res-1") :confidence 0.4)
          verdict (gov/check {} nil uncertain s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest clean-high-confidence-proposal-is-ok
  (testing "a clean, high-confidence, low-value, registered-reservation proposal is fully ok"
    (let [s (store/mem-store {"res-1" res-1})
          clean (clean-proposal :log-reservation-record "res-1")
          verdict (gov/check {} nil clean s)]
      (is (true? (:ok? verdict)))
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-tripping regression -----------------------------
;;
;; This fleet has independently rediscovered the SAME bug class in multiple
;; sibling actors: a scope-exclusion term list phrased as a bare noun
;; (e.g. "payment", "dispute", "eligibility", "access") accidentally matches
;; inside the mock advisor's own DEFAULT rationale/disclaimer text for a
;; legitimate, allowed proposal, causing the actor to self-block on its own
;; happy path. `scope-excluded-terms` above is deliberately phrased as
;; finalization/execution ACTION phrases, never a bare noun -- this test
;; exercises every op's own default (clean) advisor-generated proposal,
;; including :flag-transaction-concern (whose entire purpose is to discuss
;; payment/dispute/fraud/eligibility/access topics using exactly those bare
;; nouns), and asserts none of them ever self-trips the scope-exclusion gate.

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every allowlisted op's own default (clean) mock-advisor proposal clears the governor's scope-exclusion scan for a registered+verified reservation"
    (let [s (store/mem-store {"res-1" res-1})]
      (doseq [op [:log-reservation-record :schedule-allocation-operation
                  :coordinate-vendor-settlement :flag-transaction-concern]]
        (let [proposal (advisor/infer nil {:op op :reservation-id "res-1"
                                            :patch {:concern "customer reports a payment dispute and questions their access eligibility for res-1"
                                                    :estimated-amount 250}})
              verdict (gov/check {:reservation-id "res-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "op " op "'s own default proposal must never self-trip scope-exclusion; violations="
                   (:violations verdict)))
          (is (false? (:hard? verdict))
              (str "op " op "'s own default proposal must never HARD-hold; violations=" (:violations verdict))))))))
