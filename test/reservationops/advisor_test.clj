(ns reservationops.advisor-test
  "Unit tests of `reservationops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [reservationops.advisor :as adv]
            [reservationops.store :as store]))

(def db (store/seed-db))

(deftest propose-reservation-record-shape
  (testing "reservation-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-reservation-record
                           :reservation-id "res-1"
                           :patch {:customer "Tanaka" :status "booked"}})]
      (is (= :log-reservation-record (:op p)))
      (is (= "res-1" (:reservation-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :reservation-id)))))

(deftest propose-allocation-operation-shape
  (testing "allocation-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-allocation-operation
                           :reservation-id "res-2"
                           :patch {:item "seat-block reallocation" :urgency "routine"}})]
      (is (= :schedule-allocation-operation (:op p)))
      (is (= "res-2" (:reservation-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-vendor-settlement-shape
  (testing "vendor-settlement proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-vendor-settlement
                           :reservation-id "res-1"
                           :patch {:item "venue settlement" :estimated-amount 1200}})]
      (is (= :coordinate-vendor-settlement (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-transaction-concern-shape
  (testing "transaction-concern proposal always proposes, never actuates"
    (let [p (adv/infer db {:op :flag-transaction-concern
                           :reservation-id "res-1"
                           :patch {:concern "possible duplicate charge reported for res-1"}})]
      (is (= :flag-transaction-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-reservation-record :schedule-allocation-operation
                :coordinate-vendor-settlement :flag-transaction-concern]]
      (let [p (adv/infer db {:op op :reservation-id "res-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-reservation-record :schedule-allocation-operation
                :coordinate-vendor-settlement :flag-transaction-concern]]
      (let [p (adv/infer db {:op op :reservation-id "res-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest unknown-op-returns-empty-proposal
  (testing "an op outside the four-op set produces an unrecognized (empty) proposal shape, left for the governor to reject"
    (let [p (adv/infer db {:op :not-a-real-op :reservation-id "res-1" :patch {}})]
      (is (empty? p)))))
