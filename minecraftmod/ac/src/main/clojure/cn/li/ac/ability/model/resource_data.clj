(ns cn.li.ac.ability.model.resource-data
  "Pure-data functions for ResourceData (CP / Overload).

  Map schema:
    {:cur-cp           float   ; current CP
     :max-cp           float   ; maximum CP (from config×level)
     :cur-overload     float   ; current overload
     :max-overload     float   ; maximum overload
     :activated        bool    ; ability activation toggle
     :overload-fine    bool    ; true when overload < max (not in recovery)
     :until-recover    int     ; ticks before CP recovery starts (0 = recovering)
     :interferences    #{}     ; set of active interference source IDs (keywords)}"
  (:require [cn.li.ac.ability.config :as cfg]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn new-resource-data
  "Create fresh ResourceData. max-cp and max-overload come from config."
  ([]
   (new-resource-data (cfg/max-cp-for-level 1)
                      (cfg/max-overload-for-level 1)))
  ([max-cp max-overload]
   {:cur-cp        max-cp
    :max-cp        max-cp
    :cur-overload  0.0
    :max-overload  max-overload
    :activated     false
    :overload-fine true
    :until-recover 0
    :interferences #{}}))

;; ============================================================================
;; Activation
;; ============================================================================

(defn is-activated? [d] (boolean (:activated d)))

(defn set-activated [d v] (assoc d :activated (boolean v)))

(defn set-cur-cp [d v]
  (assoc d :cur-cp (max 0.0 (double v))))

(defn set-cur-overload [d v]
  (assoc d :cur-overload (max 0.0 (double v))))

(defn set-until-recover [d ticks]
  (assoc d :until-recover (max 0 (int ticks))))

;; ============================================================================
;; Usage Guard
;; ============================================================================

(defn can-use-ability?
  "True when activated && not in overload recovery && no interference."
  [d]
  (and (is-activated? d)
       (:overload-fine d)
       (empty? (:interferences d))))

;; ============================================================================
;; Resource Consumption
;; ============================================================================

(defn can-perform?
  "Returns true if (overload, cp) can be consumed. creative? bypasses CP check."
  [d overload cp creative?]
  (and (or creative? (>= (:cur-cp d) (double cp)))
       (can-use-ability? d)))

(defn consume-cp
  "Deduct cp from cur-cp, set until-recover to cooldown ticks.
  Returns updated ResourceData."
  [d cp cp-recover-cooldown]
  (-> d
      (update :cur-cp #(max 0.0 (- % (double cp))))
      (assoc  :until-recover cp-recover-cooldown)))

(defn add-overload
  "Add amount to cur-overload. Returns [updated-data, overloaded?]."
  [d amount]
  (let [new-val  (+ (:cur-overload d) (double amount))
        max-val  (:max-overload d)
        capped   (min new-val max-val)
        hit-cap? (>= new-val max-val)]
    [(assoc d
            :cur-overload  capped
            :overload-fine (not hit-cap?))
     hit-cap?]))

;; ============================================================================
;; Recovery Tick
;; ============================================================================

(defn tick-cp-recovery
  "One tick of CP recovery. Returns updated ResourceData."
  [d recover-speed]
  (let [until (:until-recover d)]
    (cond
      ;; Still in post-use cooldown
      (pos? until)
      (update d :until-recover dec)

      ;; Full – nothing to do
      (>= (:cur-cp d) (:max-cp d))
      d

      ;; Recovering
      :else
      (let [rate  (double recover-speed)
            delta (* rate (:max-cp d))]
        (update d :cur-cp #(min (:max-cp d) (+ % delta)))))))

(defn tick-overload-recovery
  "One tick of overload recovery. Returns updated ResourceData."
  [d recover-speed overload-recover-cooldown]
  ;; Only recover when overload is active (overload-fine = false)
  (if (:overload-fine d)
    d
    (let [new-val (max 0.0 (- (:cur-overload d) (double recover-speed)))]
      (if (zero? new-val)
        (assoc d :cur-overload 0.0 :overload-fine true)
        (assoc d :cur-overload new-val)))))

;; ============================================================================
;; Interference
;; ============================================================================

(defn add-interference [d src-id]
  (update d :interferences conj src-id))

(defn remove-interference [d src-id]
  (update d :interferences disj src-id))

(defn has-interference? [d src-id]
  (contains? (:interferences d) src-id))

;; ============================================================================
;; Max-value recalculation (called on level change)
;; ============================================================================

(defn recalc-max-values
  "After level change, clamp cur values to new maxes."
  [d new-max-cp new-max-overload]
  (assoc d
         :max-cp        new-max-cp
         :max-overload  new-max-overload
         :cur-cp        (min (:cur-cp d) new-max-cp)
         :cur-overload  (min (:cur-overload d) new-max-overload)))
