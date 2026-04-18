(ns cn.li.ac.ability.fx
  "Tiny, data-first FX helper.

  A skill spec can declare:
    :fx {:start   {:topic kw :payload (fn [evt] map)}
         :update  {:topic kw :payload (fn [evt] map)}
         :perform {:topic kw :payload (fn [evt] map)}
         :end     {:topic kw :payload (fn [evt] map)}}"
  (:require [cn.li.ac.ability.state.context :as ctx]
            [cn.li.mcmod.util.log :as log]))

(defn- safe-payload [payload-fn evt]
  (try
    (let [p (when (fn? payload-fn) (payload-fn evt))]
      (when (map? p) p))
    (catch Exception e
      (log/warn "FX payload fn failed" (ex-message e))
      nil)))

(defn send!
  "Send a named FX event (:start/:update/:perform/:end) for ctx-id.
  No-op if spec doesn't declare it."
  [ctx-id fx-spec k evt]
  (when-let [{:keys [topic payload]} (get fx-spec k)]
    (when (keyword? topic)
      (let [base {:mode k
                  :skill-id (:skill-id evt)
                  :player-id (:player-id evt)
                  :ctx-id ctx-id}
            p (merge base (or (safe-payload payload evt) {}))]
        (ctx/ctx-send-to-client! ctx-id topic p)))))

