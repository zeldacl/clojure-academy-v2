(ns cn.li.ac.ability.passive
  "Helpers for passive skill calc-event wiring."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.model.ability :as adata]))

(defonce ^:private registered-handlers (atom #{}))
(defonce ^:private registered-handlers-frozen? (atom false))

(defn- assert-registry-open!
  []
  (when @registered-handlers-frozen?
    (throw (ex-info "Passive handler registry is frozen" {}))))

(defn passive-handler-registry-snapshot
  []
  @registered-handlers)

(defn reset-passive-handler-registry-for-test!
  ([]
   (reset-passive-handler-registry-for-test! #{}))
  ([snapshot]
   (reset! registered-handlers (or snapshot #{}))
   (reset! registered-handlers-frozen? false)
   nil))

(defn freeze-passive-handler-registry!
  []
  (reset! registered-handlers-frozen? true)
  nil)

(defn learned-skill?
  [uuid skill-id]
  (boolean
    (when-let [state (ps/get-player-state uuid)]
      (adata/is-learned? (:ability-data state) skill-id))))

(defn register-passive-calc-handler!
  "Register a calc-event transform for one passive skill.
  transform-fn receives (current-value event-map) and must return a number."
  [handler-id event-type skill-id transform-fn]
  (when (keyword? handler-id)
    (if (contains? @registered-handlers handler-id)
      nil
      (do
        (assert-registry-open!)
        (swap! registered-handlers conj handler-id)
        (evt/subscribe-ability-event!
          event-type
          (fn [event]
            (let [uuid (:uuid event)
                  value (double (or (:value event) 0.0))]
              (if (and uuid (learned-skill? uuid skill-id))
                (double (transform-fn value event))
                value))))
        true))))
