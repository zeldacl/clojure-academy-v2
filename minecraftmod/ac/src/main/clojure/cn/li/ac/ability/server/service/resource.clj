(ns cn.li.ac.ability.server.service.resource
  "Pure functions: take ResourceData map, return updated map + events to fire.
  The caller (player-state.clj) applies the returned state and fires events."
  (:require [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Activation Toggle
;; ============================================================================

(defn set-activated
  "Set activation state. Returns {:data updated :events [...]}."
  [res-data uuid v]
  (let [was (rdata/is-activated? res-data)
        now (boolean v)]
    (if (= was now)
      {:data res-data :events []}
      {:data   (rdata/set-activated res-data now)
       :events [(if now
                  (evt/make-activate-event uuid)
                  (evt/make-deactivate-event uuid))]})))

;; ============================================================================
;; Max-value recalculation (fires CalcEvents for passive skill modifiers)
;; ============================================================================

(defn recalc-max-for-level
  "Recalc max-cp/max-overload with CalcEvent modifiers.
  Fires CALC-MAX-CP and CALC-MAX-OVERLOAD so passive skills can adjust."
  [res-data level]
  (let [base     (rdata/recalc-max-values res-data level)
        max-cp   (evt/fire-calc-event! evt/CALC-MAX-CP (:max-cp base) {})
        max-ol   (evt/fire-calc-event! evt/CALC-MAX-OVERLOAD (:max-overload base) {})]
    (assoc base
           :max-cp       max-cp
           :max-overload max-ol
           :cur-cp       (min (:cur-cp base) max-cp)
           :cur-overload (min (:cur-overload base) max-ol))))

;; ============================================================================
;; Consumption
;; ============================================================================

(defn perform-resource
  "Attempt to consume (overload, cp). Returns {:data updated :success? bool :events [...]}.
  
  creative? – when true, skip CP check (creative mode bypass).
  level    – player's ability level (for growth caps)."
  [res-data uuid overload cp creative? level]
  (if-not (rdata/can-perform? res-data overload cp creative?)
    {:data res-data :success? false :events []}
    (let [;; Fire CALC-SKILL-PERFORM to let passive skills modify cp/overload
          effective-cp  (evt/fire-calc-event! evt/CALC-SKILL-PERFORM cp
                                              {:field :cp :uuid uuid})
          effective-ol  (evt/fire-calc-event! evt/CALC-SKILL-PERFORM overload
                                              {:field :overload :uuid uuid})
          ;; Apply CP consumption
          data1          (if (and (pos? effective-cp) (not creative?))
                           (rdata/consume-cp res-data effective-cp cfg/*cp-recover-cooldown*)
                           res-data)
          ;; Apply overload
          [data2 hit-cap?] (if (pos? effective-ol)
                              (rdata/add-overload data1 effective-ol cfg/*overload-recover-cooldown*)
                              [data1 false])
          ;; Growth: add-max-cp grows by consumed CP × rate
          data3          (if (and (pos? effective-cp) (not creative?))
                           (rdata/grow-max-cp data2 effective-cp cfg/*maxcp-incr-rate* level)
                           data2)
          ;; Growth: add-max-overload grows by overload × rate
          data4          (if (pos? effective-ol)
                           (rdata/grow-max-overload data3 effective-ol cfg/*maxo-incr-rate* level)
                           data3)
          ;; Recalc max after growth
          data5          (recalc-max-for-level data4 level)
          events         (when hit-cap? [(evt/make-overload-event uuid)])]
      {:data     data5
       :success? true
       :events   (or events [])})))

;; ============================================================================
;; Server Tick (called every player tick on server side)
;; ============================================================================

(defn server-tick
  "Advance one server tick: recover CP and overload.
  Returns updated ResourceData map (pure, no events for tick)."
  [res-data]
  (let [cp-speed       (evt/fire-calc-event! evt/CALC-CP-RECOVER-SPEED
                                              cfg/*cp-recover-speed* {})
        ov-speed       (evt/fire-calc-event! evt/CALC-OVERLOAD-RECOVER-SPEED
                                              cfg/*overload-recover-speed* {})]
    {:data (-> res-data
               (rdata/tick-cp-recovery cp-speed)
               (rdata/tick-overload-recovery ov-speed))
     :events []}))

;; ============================================================================
;; Interference
;; ============================================================================

(defn add-interference
  [res-data src-id]
  (rdata/add-interference res-data src-id))

(defn remove-interference
  [res-data src-id]
  (rdata/remove-interference res-data src-id))
