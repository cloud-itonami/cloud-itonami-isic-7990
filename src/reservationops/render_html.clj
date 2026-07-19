(ns reservationops.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (reservationops.operation ->
  reservationops.governor -> reservationops.store) through the SAME
  scenario `reservationops.sim` already proves end-to-end (every id and
  op below is read from `reservationops.store/demo-data` and
  `reservationops.governor` -- none invented for this renderer).
  No invented numbers, no timestamps, byte-identical across reruns."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [reservationops.store :as store]
            [reservationops.operation :as op]
            [reservationops.advisor :as advisor]
            [reservationops.phase :as phase]
            [reservationops.governor :as governor]
            [langgraph.graph :as g]))

(def ^:private manager-phase-1
  {:actor-id "mgr-1" :actor-role :reservation-desk-manager :phase 1})

(def ^:private manager-phase-3
  {:actor-id "mgr-1" :actor-role :reservation-desk-manager :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "reservation-desk-manager-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real OperationActor StateGraph through the same scenario
  `reservationops.sim` uses (verified against the real store/governor
  data first, found trustworthy -- ids res-1/res-2/res-3 and ops all
  come straight from `reservationops.store/demo-data` and
  `reservationops.governor/allowed-ops`). Covers:
    - a phase-1 write that needs human approval (phase-approval escalate)
    - a phase-3 clean auto-commit (:log-reservation-record)
    - a phase-3 clean auto-commit (:schedule-allocation-operation)
    - a phase-3 clean, under-threshold auto-commit (:coordinate-vendor-settlement)
    - a phase-3 over-threshold vendor settlement (ALWAYS escalates -- high-stakes) + human approval
    - :flag-transaction-concern (ALWAYS escalates regardless of phase -- high-stakes) + human approval
    - three DISTINCT real HARD-hold reasons: :reservation-unverified
      (unregistered reservation), :effect-not-propose (advisor attempts
      direct actuation), :scope-excluded (advisor drifts into the
      permanently-excluded payment-dispute/access-eligibility territory)
  Returns the resulting db."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1" {:op :log-reservation-record :reservation-id "res-1"
                        :patch {:customer "Tanaka" :tickets 2 :status "booked"}}
           manager-phase-1)
    (approve! actor "t1")

    (exec! actor "t2" {:op :log-reservation-record :reservation-id "res-1"
                        :patch {:customer "Tanaka" :status "checked-in"}}
           manager-phase-3)

    (exec! actor "t3" {:op :schedule-allocation-operation :reservation-id "res-1"
                        :patch {:item "seat-block reallocation" :urgency "routine"}}
           manager-phase-3)

    (exec! actor "t4" {:op :coordinate-vendor-settlement :reservation-id "res-2"
                        :patch {:item "venue-b quarterly settlement" :estimated-amount 1200}}
           manager-phase-3)

    (exec! actor "t4b" {:op :coordinate-vendor-settlement :reservation-id "res-2"
                         :patch {:item "annual bulk settlement run" :estimated-amount 9000}}
           manager-phase-3)
    (approve! actor "t4b")

    (exec! actor "t5" {:op :flag-transaction-concern :reservation-id "res-1"
                        :patch {:concern "possible duplicate charge reported by customer for res-1"
                                :confidence 0.92}}
           manager-phase-3)
    (approve! actor "t5")

    ;; HARD hold 1/3 -- :reservation-unverified (reservation does not exist at all)
    (exec! actor "t6" {:op :log-reservation-record :reservation-id "res-99"
                        :patch {:customer "unknown"}}
           manager-phase-3)

    ;; same rule, distinct concrete cause -- registered but not yet verified
    (exec! actor "t7" {:op :log-reservation-record :reservation-id "res-3"
                        :patch {:customer "unknown"}}
           manager-phase-3)

    ;; HARD hold 2/3 -- :effect-not-propose (advisor attempts direct actuation)
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "t8" {:op :schedule-allocation-operation :reservation-id "res-1"
                                 :patch {:item "seat reassignment"}}
             manager-phase-3))

    ;; HARD hold 3/3 -- :scope-excluded (advisor drifts into permanently
    ;; excluded payment-dispute-resolution-finalization /
    ;; access-eligibility-override-finalization territory)
    (exec! actor "t9" {:op :log-reservation-record :reservation-id "res-1"
                        :out-of-scope? true
                        :patch {}}
           manager-phase-3)

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for [ledger reservation-id]
  (last (filter #(= reservation-id (:reservation-id %)) ledger)))

(defn- status-cell [fact]
  (cond
    (nil? fact)                        ["muted" "in progress"]
    (= :committed (:t fact))           ["ok" "committed"]
    (= :approval-rejected (:t fact))   ["err" "approval-rejected"]
    (= :governor-hold (:t fact))       ["err" (str "hold: " (str/join "," (map name (:basis fact))))]
    :else                              ["muted" "in progress"]))

(defn- bool-cell [b]
  (if b ["ok" "yes"] ["err" "no"]))

;; ----------------------------- tables -----------------------------

(defn- reservation-directory-table [db]
  (str "<table><thead><tr><th>reservation id</th><th>name</th><th>kind</th>"
       "<th>registered?</th><th>verified?</th><th>latest ledger status</th></tr></thead><tbody>\n"
       (str/join "\n"
         (for [r (store/all-reservations db)
               :let [fact (last-fact-for (store/ledger db) (:reservation-id r))
                     [rcls rtxt] (bool-cell (:registered? r))
                     [vcls vtxt] (bool-cell (:verified? r))
                     [scls stxt] (status-cell fact)]]
           (str "<tr><td><code>" (esc (:reservation-id r)) "</code></td><td>" (esc (:name r))
                "</td><td>" (esc (name (:kind r)))
                "</td><td><span class=\"" rcls "\">" rtxt "</span></td>"
                "<td><span class=\"" vcls "\">" vtxt "</span></td>"
                "<td><span class=\"" scls "\">" (esc stxt) "</span></td></tr>")))
       "\n</tbody></table>"))

(defn- coordination-log-table [db]
  (str "<table><thead><tr><th>op</th><th>reservation id</th><th>payload</th></tr></thead><tbody>\n"
       (str/join "\n"
         (for [r (store/coordination-log db)]
           (str "<tr><td><code>" (esc (name (:op r))) "</code></td><td><code>"
                (esc (:reservation-id r)) "</code></td><td>" (esc (pr-str (:payload r))) "</td></tr>")))
       "\n</tbody></table>"))

(defn- action-gate-table []
  (let [{:keys [label writes auto]} (get phase/phases phase/default-phase)]
    (str "<table><thead><tr><th>op</th><th>allowed at phase " phase/default-phase
         " (" (esc label) ")</th><th>auto-commit eligible</th><th>always escalates (high-stakes)</th></tr></thead><tbody>\n"
         (str/join "\n"
           (for [op-kw (sort governor/allowed-ops)
                 :let [[wcls wtxt] (bool-cell (contains? writes op-kw))
                       [acls atxt] (bool-cell (contains? auto op-kw))
                       [ecls etxt] (bool-cell (contains? governor/always-escalate-ops op-kw))]]
             (str "<tr><td><code>" (esc (name op-kw)) "</code></td>"
                  "<td><span class=\"" wcls "\">" wtxt "</span></td>"
                  "<td><span class=\"" acls "\">" atxt "</span></td>"
                  "<td><span class=\"" ecls "\">" etxt "</span></td></tr>")))
         "\n</tbody></table>"
         "<p class=\"muted\">high-value guardrail: a <code>:coordinate-vendor-settlement</code> "
         "proposal whose <code>:value :estimated-amount</code> exceeds "
         (esc governor/high-value-threshold) " USD always escalates too, regardless of confidence.</p>")))

(defn- audit-ledger-table [db]
  (str "<table><thead><tr><th>type</th><th>op</th><th>reservation id</th><th>disposition</th>"
       "<th>basis / rule</th><th>confidence</th></tr></thead><tbody>\n"
       (str/join "\n"
         (for [f (store/ledger db)
               :let [[cls _] (status-cell f)]]
           (str "<tr><td><code>" (esc (name (:t f))) "</code></td>"
                "<td><code>" (esc (name (:op f))) "</code></td>"
                "<td><code>" (esc (:reservation-id f)) "</code></td>"
                "<td><span class=\"" cls "\">" (esc (name (or (:disposition f) :commit))) "</span></td>"
                "<td>" (esc (if (seq (:basis f)) (str/join "," (map name (:basis f))) "-")) "</td>"
                "<td>" (esc (or (:confidence f) "-")) "</td></tr>")))
       "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str "<!doctype html>\n<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
       "<title>reservationops.render-html -- Reservation Ops Governor operator console</title>\n"
       "<style>\n" css "\n</style>\n</head>\n<body>\n"
       "<header class=\"bar\"><h1>Reservation Ops Governor -- Operator Console</h1>"
       "<span class=\"badge\">ISIC 7990 &middot; phase " phase/default-phase "</span></header>\n<main>\n"
       "<div class=\"card\"><h2>Reservation / vendor-contract directory</h2>"
       (reservation-directory-table db) "</div>\n"
       "<div class=\"card\"><h2>Committed coordination log</h2>"
       (coordination-log-table db) "</div>\n"
       "<div class=\"card\"><h2>Action gate (phase " phase/default-phase " -- closed op allowlist)</h2>"
       (action-gate-table) "</div>\n"
       "<div class=\"card\"><h2>Audit ledger</h2>"
       (audit-ledger-table db) "</div>\n"
       "</main>\n</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out)))
