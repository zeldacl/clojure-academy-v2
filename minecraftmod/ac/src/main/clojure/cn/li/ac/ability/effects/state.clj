(ns cn.li.ac.ability.effects.state
  (:require 
            [cn.li.ac.ability.service.state-accessors :as ps-accessors]
[cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.model.resource :as rdata]))

(defn execute-assoc-state!
  [evt {:keys [k v]}]
  (ctx/update-context! (:ctx-id evt) assoc-in [:skill-state k] v)
  evt)

(defn execute-charge-tick!
  [evt {:keys [k max]}]
  (let [ctx-id (:ctx-id evt)
        state-key (or k :charge-ticks)
        next-tick (min (long (or max Long/MAX_VALUE))
                       (inc (long (or (get-in (ctx/get-context ctx-id) [:skill-state state-key]) 0))))]
    (ctx/update-context! ctx-id assoc-in [:skill-state state-key] next-tick)
    (assoc evt state-key next-tick)))

(defn execute-terminate!
  [evt _params]
  (ctx/terminate-context! (:ctx-id evt) nil)
  evt)

(defn execute-overload-floor!
  "Enforce a minimum overload value for the player.
  If the player's current overload is below :floor, it is raised to :floor.
  :floor may be a number or a fn of evt."
  [evt {:keys [floor]}]
  (let [floor-val (double (if (fn? floor) (floor evt) (or floor 0.0)))]
    (ps-accessors/update-resource-data!
     (:player-id evt)
     (fn [res-data]
       (if (< (double (get res-data :cur-overload 0.0)) floor-val)
         (-> res-data (rdata/set-cur-overload floor-val) (assoc :overload-fine true))
         res-data)))
    evt))




