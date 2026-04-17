(ns cn.li.ac.ability.effect.state
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.effect :as effect]))

(effect/defop :assoc-state
  [evt {:keys [k v]}]
  (ctx/update-context! (:ctx-id evt) assoc-in [:skill-state k] v)
  evt)

(effect/defop :charge-tick
  [evt {:keys [k max]}]
  (let [ctx-id (:ctx-id evt)
        state-key (or k :charge-ticks)
        next-tick (min (long (or max Long/MAX_VALUE))
                       (inc (long (or (get-in (ctx/get-context ctx-id) [:skill-state state-key]) 0))))]
    (ctx/update-context! ctx-id assoc-in [:skill-state state-key] next-tick)
    (assoc evt state-key next-tick)))

(effect/defop :terminate
  [evt _]
  (ctx/terminate-context! (:ctx-id evt) nil)
  evt)
