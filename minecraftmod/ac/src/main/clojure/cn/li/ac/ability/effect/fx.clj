(ns cn.li.ac.ability.effect.fx
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.effect :as effect]))

(effect/defop :fx
  [evt {:keys [topic payload to]}]
  (when (keyword? topic)
    (let [ctx-id (:ctx-id evt)
          body (merge {:skill-id (:skill-id evt)}
                      (if (fn? payload) (or (payload evt) {}) (or payload {})))]
      (case (or to :client)
        :client (ctx/ctx-send-to-client! ctx-id topic body)
        :self (ctx/ctx-send-to-self! ctx-id topic body)
        :except-local (ctx/ctx-send-to-except-local! ctx-id topic body)
        (ctx/ctx-send-to-client! ctx-id topic body))))
  evt)
