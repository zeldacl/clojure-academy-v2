(ns cn.li.ac.ability.service.resource
  "Pure functions: take ResourceData map, return updated map + events to fire.
  The caller (player-state.clj) applies the returned state and fires events."
  (:require [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.ac.ability.event :as evt]
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
;; Consumption
;; ============================================================================

(defn perform-resource
  "Attempt to consume (overload, cp). Returns {:data updated :success? bool :events [...]}.
  
  creative? – when true, skip CP check (creative mode bypass)."
  [res-data uuid overload cp creative?]
  (if-not (rdata/can-perform? res-data overload cp creative?)
    {:data res-data :success? false :events []}
    (let [;; Apply CP consumption
          data1          (if (and (pos? cp) (not creative?))
                           (rdata/consume-cp res-data cp cfg/*cp-recover-cooldown*)
                           res-data)
          ;; Apply overload
          [data2 hit-cap?] (if (pos? overload)
                              (rdata/add-overload data1 overload)
                              [data1 false])
          events         (when hit-cap? [(evt/make-overload-event uuid)])]
      {:data     data2
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
               (rdata/tick-overload-recovery ov-speed cfg/*overload-recover-cooldown*))
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
