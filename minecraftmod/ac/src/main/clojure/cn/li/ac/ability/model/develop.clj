(ns cn.li.ac.ability.model.develop
  "Pure-data functions for DevelopData (timed learning state machine).

  DevelopData tracks the progress of a learning session (learning a skill or
  leveling up) that requires the player to interact with a Developer device
  over time.

  Map schema:
    {:state          keyword ; :idle | :developing | :done | :failed
     :developer-type keyword ; :portable | :normal | :advanced
     :action-type    keyword ; :learn-skill | :level-up
     :action-data    map     ; {:skill-id kw} or {:target-level int}
     :stim           int     ; completed stim units
     :max-stim       int     ; total stim units required
     :tick-this-stim int     ; ticks accumulated in current stim unit
     :energy-consumed float} ; total energy consumed this session"
  (:require [cn.li.ac.ability.config :as cfg]))

;; ============================================================================
;; Developer types (mirrors original DeveloperType.java)
;; ============================================================================

(def developer-types
  "Developer device specifications.
  :energy   - total energy capacity
  :tps      - ticks per stim (higher = slower)
  :cps      - energy consumed per stim
  :bandwidth - learning bandwidth multiplier (unused for now, reserved)"
  {:portable {:energy 10000.0 :tps 25 :cps 750.0 :bandwidth 0.3}
   :normal   {:energy 50000.0 :tps 20 :cps 700.0 :bandwidth 0.7}
   :advanced {:energy 200000.0 :tps 15 :cps 600.0 :bandwidth 1.0}})

;; ============================================================================
;; Stim count formulas (matches original)
;; ============================================================================

(defn skill-learning-stims
  "Stim count to learn a skill: 3 + level² × 0.5"
  [skill-level]
  (int (Math/ceil (+ 3.0 (* skill-level skill-level 0.5)))))

(defn level-up-stims
  "Stim count to level up: 5 × (current-level + 1)"
  [current-level]
  (* 5 (inc current-level)))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn new-develop-data
  "Create idle DevelopData."
  []
  {:state          :idle
   :developer-type nil
   :action-type    nil
   :action-data    nil
   :stim           0
   :max-stim       0
   :tick-this-stim 0
   :energy-consumed 0.0})

(defn start-develop
  "Begin a development session. Returns updated DevelopData.
  action-type: :learn-skill or :level-up
  action-data: {:skill-id kw} or {:target-level int}
  max-stim: computed from skill-learning-stims or level-up-stims"
  [d developer-type action-type action-data max-stim]
  (assoc d
         :state          :developing
         :developer-type developer-type
         :action-type    action-type
         :action-data    action-data
         :stim           0
         :max-stim       (int max-stim)
         :tick-this-stim 0
         :energy-consumed 0.0))

;; ============================================================================
;; Tick
;; ============================================================================

(defn energy-per-tick
  "Energy consumed per tick: CPS / TPS"
  [developer-type]
  (let [dt (get developer-types developer-type)]
    (/ (:cps dt) (:tps dt))))

(defn tick-develop
  "Advance one tick of development.
  Returns updated DevelopData. When :stim reaches :max-stim, state → :done."
  [d]
  (if (not= :developing (:state d))
    d
    (let [dt        (get developer-types (:developer-type d))
          tps       (int (:tps dt))
          ept       (/ (:cps dt) (double tps))
          new-tick  (inc (:tick-this-stim d))
          new-energy (+ (:energy-consumed d) ept)]
      (if (>= new-tick tps)
        ;; Completed one stim unit
        (let [new-stim (inc (:stim d))]
          (if (>= new-stim (:max-stim d))
            ;; Done
            (assoc d
                   :state          :done
                   :stim           new-stim
                   :tick-this-stim 0
                   :energy-consumed new-energy)
            ;; Continue
            (assoc d
                   :stim           new-stim
                   :tick-this-stim 0
                   :energy-consumed new-energy)))
        ;; Still accumulating ticks in this stim
        (assoc d
               :tick-this-stim new-tick
               :energy-consumed new-energy)))))

;; ============================================================================
;; State queries
;; ============================================================================

(defn developing? [d] (= :developing (:state d)))
(defn done?       [d] (= :done (:state d)))
(defn failed?     [d] (= :failed (:state d)))
(defn idle?       [d] (= :idle (:state d)))

(defn progress
  "Returns development progress as a float [0.0, 1.0]."
  [d]
  (if (<= (:max-stim d) 0)
    0.0
    (/ (double (:stim d)) (double (:max-stim d)))))

(defn abort
  "Abort development, returning to idle."
  [d]
  (new-develop-data))

(defn fail
  "Mark development as failed."
  [d]
  (assoc d :state :failed))

(defn complete-and-reset
  "After processing a :done result, reset to idle."
  [d]
  (new-develop-data))
