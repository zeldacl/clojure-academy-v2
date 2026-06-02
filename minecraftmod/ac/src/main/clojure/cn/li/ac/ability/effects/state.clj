(ns cn.li.ac.ability.effects.state
  (:require [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- resolve-session-id
  [evt]
  (or (runtime-hooks/player-state-session-id)
      (let [ctx-map (ctx/get-context (:ctx-id evt))
            sid (:session-id ctx-map)]
        (if (vector? sid) (first sid) sid))
      (runtime-hooks/require-player-state-session-id "effects-state")))

(defn execute-assoc-state!
  [evt {:keys [k v]}]
  (command-rt/run-command-in-session!
   (resolve-session-id evt)
   (:player-id evt)
   {:command :context-assoc-skill-state
    :ctx-id (:ctx-id evt)
    :k k
    :v v})
  evt)

(defn execute-charge-tick!
  [evt {:keys [k max]}]
  (let [ctx-id (:ctx-id evt)
        state-key (or k :charge-ticks)
        result (command-rt/run-command-in-session!
                (resolve-session-id evt)
                (:player-id evt)
                {:command :context-increment-skill-state
                 :ctx-id ctx-id
                 :k state-key
                 :max max})
  next-tick (long (or (get-in result [:state :context-registry ctx-id :skill-state state-key]) 0))]
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
    (command-rt/run-command-in-session!
     (resolve-session-id evt)
     (:player-id evt)
     {:command :enforce-overload-floor
      :floor-value floor-val})
    evt))




