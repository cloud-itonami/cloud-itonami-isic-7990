(ns reservationops.store-contract-test
  "Contract tests for `reservationops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [reservationops.store :as store]))

(deftest mem-store-reservation-lookup
  (testing "MemStore can store and retrieve reservations by ID (string keys)"
    (let [reservations {"r1" {:reservation-id "r1" :name "Reservation 1" :registered? true :verified? true}}
          s (store/mem-store reservations)]
      (is (some? (store/reservation s "r1")))
      (is (nil? (store/reservation s "r99"))))))

(deftest mem-store-all-reservations
  (testing "MemStore returns all reservations in sorted order"
    (let [reservations {"r2" {:reservation-id "r2" :name "Reservation 2"}
                        "r1" {:reservation-id "r1" :name "Reservation 1"}
                        "r3" {:reservation-id "r3" :name "Reservation 3"}}
          s (store/mem-store reservations)
          all-r (store/all-reservations s)]
      (is (= 3 (count all-r)))
      (is (= "r1" (:reservation-id (first all-r))))
      (is (= "r3" (:reservation-id (last all-r)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-reservation-record :reservation-id "r1" :value {:customer "test"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-reservations
  (testing "MemStore with-reservations replaces the reservation directory"
    (let [s (store/mem-store {})
          new-reservations {"r1" {:reservation-id "r1" :name "Reservation 1"}}]
      (is (= 0 (count (store/all-reservations s))))
      (store/with-reservations s new-reservations)
      (is (= 1 (count (store/all-reservations s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo reservations"
    (let [s (store/seed-db)]
      (is (> (count (store/all-reservations s)) 0))
      (is (some? (store/reservation s "res-1")))
      (is (some? (store/reservation s "res-2")))
      (is (some? (store/reservation s "res-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for reservation-id"
    (let [demo (store/demo-data)
          reservations (:reservations demo)]
      (doseq [[k v] reservations]
        (is (string? k) "keys must be strings")
        (is (string? (:reservation-id v)) "reservation-id must be string")
        (is (= k (:reservation-id v)) "key must match reservation-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
