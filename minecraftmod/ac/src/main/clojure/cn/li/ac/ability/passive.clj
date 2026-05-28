(ns cn.li.ac.ability.passive
  "Helpers for passive skill calc-event wiring."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.model.ability :as adata]))

(defn default-passive-handler-runtime-state
  []
  {:registered-handlers #{}
   :frozen? false})

(defn create-passive-handler-runtime
  ([]
   (create-passive-handler-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-passive-handler-runtime-state))}}]
   {::runtime ::passive-handler-runtime
    :state* state*}))

(def ^:dynamic *passive-handler-runtime* nil)

(defonce ^:private installed-passive-handler-runtime
  (create-passive-handler-runtime))

(defn- passive-handler-runtime?
  [runtime]
  (and (map? runtime)
       (= ::passive-handler-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-passive-handler-runtime
  [runtime f]
  (when-not (passive-handler-runtime? runtime)
    (throw (ex-info "Expected passive handler runtime"
                    {:runtime runtime})))
  (binding [*passive-handler-runtime* runtime]
    (f)))

(defmacro with-passive-handler-runtime
  [runtime & body]
  `(call-with-passive-handler-runtime ~runtime (fn [] ~@body)))

(defn- current-passive-handler-runtime
  []
  (or *passive-handler-runtime*
      installed-passive-handler-runtime))

(defn- passive-handler-state-atom
  []
  (:state* (current-passive-handler-runtime)))

(defn- passive-handler-state-snapshot
  []
  @(passive-handler-state-atom))

(defn- update-passive-handler-state!
  [f & args]
  (apply swap! (passive-handler-state-atom) f args))

(defn- assert-registry-open!
  []
  (when (:frozen? (passive-handler-state-snapshot))
    (throw (ex-info "Passive handler registry is frozen" {}))))

(defn passive-handler-registry-snapshot
  []
  (:registered-handlers (passive-handler-state-snapshot)))

(defn reset-passive-handler-registry-for-test!
  ([]
   (reset-passive-handler-registry-for-test! #{}))
  ([snapshot]
   (reset! (passive-handler-state-atom)
           {:registered-handlers (or snapshot #{})
            :frozen? false})
   nil))

(defn freeze-passive-handler-registry!
  []
  (update-passive-handler-state! assoc :frozen? true)
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
    (if (contains? (:registered-handlers (passive-handler-state-snapshot)) handler-id)
      nil
      (do
        (assert-registry-open!)
        (update-passive-handler-state! update :registered-handlers conj handler-id)
        (evt/subscribe-ability-event!
          event-type
          (fn [event]
            (let [uuid (:uuid event)
                  value (double (or (:value event) 0.0))]
              (if (and uuid (learned-skill? uuid skill-id))
                (double (transform-fn value event))
                value))))
        true))))
