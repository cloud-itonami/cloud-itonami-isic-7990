(ns reservationops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean reservation-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then an allocation-operation scheduling
  request and a vendor-settlement coordination request (both
  auto-commit clean at phase 3), then a transaction-concern flag
  (ALWAYS escalates, at any phase -- approve, then commit), then
  HARD-hold scenarios: an unregistered reservation, a reservation
  registered but not yet verified, a proposal whose own `:effect` is
  not `:propose`, and a proposal that has drifted into the
  permanently-excluded payment-dispute-resolution-finalization /
  access-eligibility-override-finalization scope."
  (:require [langgraph.graph :as g]
            [reservationops.advisor :as advisor]
            [reservationops.store :as store]
            [reservationops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "reservation-desk-manager-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        manager-phase-1 {:actor-id "mgr-1" :actor-role :reservation-desk-manager :phase 1}
        manager-phase-3 {:actor-id "mgr-1" :actor-role :reservation-desk-manager :phase 3}
        actor (op/build db)]

    (println "== log-reservation-record res-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-reservation-record :reservation-id "res-1"
                                  :patch {:customer "Tanaka" :tickets 2 :status "booked"}} manager-phase-1)]
      (println r)
      (println "-- human reservation-desk manager approves --")
      (println (approve! actor "t1")))

    (println "\n== log-reservation-record res-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-reservation-record :reservation-id "res-1"
                                  :patch {:customer "Tanaka" :status "checked-in"}} manager-phase-3))

    (println "\n== schedule-allocation-operation res-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-allocation-operation :reservation-id "res-1"
                                  :patch {:item "seat-block reallocation" :urgency "routine"}} manager-phase-3))

    (println "\n== coordinate-vendor-settlement res-2 (phase 3, clean, under threshold -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-vendor-settlement :reservation-id "res-2"
                                  :patch {:item "venue-b quarterly settlement" :estimated-amount 1200}} manager-phase-3))

    (println "\n== coordinate-vendor-settlement res-2 (phase 3, over value threshold -- ALWAYS escalates) ==")
    (let [r (exec-op actor "t4b" {:op :coordinate-vendor-settlement :reservation-id "res-2"
                                  :patch {:item "annual bulk settlement run" :estimated-amount 9000}} manager-phase-3)]
      (println r)
      (println "-- human reservation-desk manager approves --")
      (println (approve! actor "t4b")))

    (println "\n== flag-transaction-concern res-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-transaction-concern :reservation-id "res-1"
                                 :patch {:concern "possible duplicate charge reported by customer for res-1" :confidence 0.92}} manager-phase-3)]
      (println r)
      (println "-- human reservation-desk manager reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-reservation-record res-99 (unregistered reservation -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-reservation-record :reservation-id "res-99"
                                  :patch {:customer "unknown"}} manager-phase-3))

    (println "\n== log-reservation-record res-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-reservation-record :reservation-id "res-3"
                                  :patch {:customer "unknown"}} manager-phase-3))

    (println "\n== schedule-allocation-operation res-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-allocation-operation :reservation-id "res-1"
                                           :patch {:item "seat reassignment"}} manager-phase-3)))

    (println "\n== log-reservation-record res-1, advisor drifts into payment-dispute/access-eligibility scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-reservation-record :reservation-id "res-1"
                                   :out-of-scope? true
                                   :patch {}} manager-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
