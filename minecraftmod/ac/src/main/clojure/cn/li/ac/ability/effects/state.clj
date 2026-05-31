(ns cn.li.ac.ability.effects.state
  (:require 
            [cn.li.ac.ability.service.command-runtime :as command-rt]
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
  (let [result (command-rt/run-command-in-session!
                (resolve-session-id evt)
                (:player-id evt)
                {:command :context-assoc-skill-state
                 :ctx-id (:ctx-id evt)
                 :k k
                 :v v})]
    (when (= :context-not-found (:rejected-reason result))
      (let [state-key (if (vector? k) k [k])]
        (ctx/update-context! (:ctx-id evt)
                             assoc-in
                             (into [:skill-state] state-key)
                             v))))
  evt)

(defn execute-charge-tick!
  [evt {:keys [k max]}]
  (let [ctx-id (:ctx-id evt)
        state-key (or k :charge-ticks)
        state-path (if (vector? state-key) state-key [state-key])
        result (command-rt/run-command-in-session!
                (resolve-session-id evt)
                (:player-id evt)
                {:command :context-increment-skill-state
                 :ctx-id ctx-id
                 :k state-key
                 :max max})
        next-tick (if (= :context-not-found (:rejected-reason result))
                    (do
                      (ctx/update-context! ctx-id
                                           (fn [c]
                                             (let [max-v (long (or max Long/MAX_VALUE))
                                                   current (long (or (get-in c (into [:skill-state] state-path)) 0))
                                                   next-v (min max-v (inc current))]
                                               (assoc-in c (into [:skill-state] state-path) next-v))))
                      (long (or (get-in (ctx/get-context ctx-id) (into [:skill-state] state-path)) 0)))
                    (long (or (get-in result (into [:state :context-registry ctx-id :skill-state] state-path)) 0)))]
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




