(ns cn.li.ac.ability.server.damage.runtime
  "AC-owned damage interception runtime.

  Owns handler registration order and damage result normalization so forge only
  forwards platform events and writes the final amount back to the event."
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private damage-handlers
  (atom {}))

(defn- get-sorted-handlers []
  (->> @damage-handlers
       (sort-by (fn [[_handler-id data]] (:priority data)))
       (map (fn [[handler-id data]] [handler-id (:fn data)]))))

(defn register-damage-handler!
  [handler-id handler-fn priority]
  (try
    (swap! damage-handlers assoc handler-id {:fn handler-fn :priority priority})
    (log/debug "Registered damage handler:" handler-id "priority:" priority)
    true
    (catch Exception e
      (log/warn "Failed to register damage handler:" (ex-message e))
      false)))

(defn unregister-damage-handler!
  [handler-id]
  (try
    (swap! damage-handlers dissoc handler-id)
    (log/debug "Unregistered damage handler:" handler-id)
    true
    (catch Exception e
      (log/warn "Failed to unregister damage handler:" (ex-message e))
      false)))

(defn get-active-handlers []
  (keys @damage-handlers))

(defn process-damage!
  [player-id attacker-id original-damage damage-source]
  (try
    (loop [remaining-handlers (get-sorted-handlers)
           current-damage (double original-damage)]
      (if (empty? remaining-handlers)
        current-damage
        (let [[handler-id handler-fn] (first remaining-handlers)
              next-damage (try
                            (let [result (handler-fn player-id attacker-id current-damage damage-source)]
                              (if (vector? result)
                                (let [[new-damage _metadata] result]
                                  (if (number? new-damage)
                                    (double new-damage)
                                    (do
                                      (log/warn "Handler" handler-id "returned invalid damage:" new-damage)
                                      current-damage)))
                                (do
                                  (log/warn "Handler" handler-id "returned invalid result:" result)
                                  current-damage)))
                            (catch Exception e
                              (log/warn "Handler" handler-id "failed:" (ex-message e))
                              current-damage))]
          (recur (rest remaining-handlers) (double next-damage)))))
    (catch Exception e
      (log/warn "Damage interception failed:" (ex-message e))
      (double original-damage))))