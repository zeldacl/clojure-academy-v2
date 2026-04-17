(ns cn.li.ac.ability.effect.state
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.effect :as effect]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.model.resource-data :as rdata]))

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

(effect/defop :overload-floor
  "Enforce a minimum overload value for the player.
  If the player's current overload is below :floor, it is raised to :floor.
  :floor may be a number or a fn of evt."
  [evt {:keys [floor]}]
  (let [floor-val (double (if (fn? floor) (floor evt) (or floor 0.0)))]
    (ps/update-resource-data!
     (:player-id evt)
     (fn [res-data]
       (if (< (double (get res-data :cur-overload 0.0)) floor-val)
         (-> res-data (rdata/set-cur-overload floor-val) (assoc :overload-fine true))
         res-data)))
    evt))
