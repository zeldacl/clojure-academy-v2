(ns cn.li.ac.content.ability.vecmanip.vec-reflection
  "VecReflection skill - advanced reflection (toggle).

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :vec-reflection :exp] 0.0)))

(defn- get-player-position [player-id]
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn vec-reflection-on-key-down
  "Activate or deactivate toggle skill."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [is-active? (toggle/is-toggle-active? ctx-data :vec-reflection)
            exp (get-skill-exp player-id)]
        (if is-active?
          (do
            (toggle/remove-toggle! ctx-id :vec-reflection)
            (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited)
            (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-overload-keep)
            (log/info "VecReflection: Deactivated"))
          (do
            (toggle/activate-toggle! ctx-id :vec-reflection)
            (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-visited] #{})
            (let [overload-keep (scaling/lerp 350.0 250.0 exp)]
              (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-overload-keep] overload-keep)
              (when (ps/get-player-state player-id)
                (ps/update-ability-data! player-id
                                         #(update-in % [:cp-data :overload] + overload-keep))))
            (log/info "VecReflection: Activated")))))
    (catch Exception e
      (log/warn "VecReflection key-down failed:" (ex-message e)))))

(defn vec-reflection-on-key-tick
  "Tick handler - consume resources and reflect nearby projectiles."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (when (toggle/is-toggle-active? ctx-data :vec-reflection)
        (let [exp (get-skill-exp player-id)
              tick-consumption (scaling/lerp 15.0 11.0 exp)
              overload-keep (get-in ctx-data [:skill-state :vec-reflection-overload-keep] 0.0)]
          (toggle/update-toggle-tick! ctx-id :vec-reflection)

          (when-let [state (ps/get-player-state player-id)]
            (let [cp-data (get-in state [:ability-data :cp-data])
                  current-overload (:overload cp-data 0.0)]
              (when (< current-overload overload-keep)
                (ps/update-ability-data! player-id
                                         #(assoc-in % [:cp-data :overload] overload-keep)))))

          (when-let [state (ps/get-player-state player-id)]
            (let [cp-data (get-in state [:ability-data :cp-data])
                  current-cp (:cp cp-data 0.0)]
              (if (>= current-cp tick-consumption)
                (ps/update-ability-data! player-id
                                         #(update-in % [:cp-data :cp] - tick-consumption))
                (do
                  (toggle/deactivate-toggle! ctx-id :vec-reflection)
                  (log/info "VecReflection: Deactivated (insufficient CP)")))))

          (when-let [pos (get-player-position player-id)]
            (when world-effects/*world-effects*
              (let [world-id (:world-id pos)
                    x (:x pos)
                    y (:y pos)
                    z (:z pos)
                    entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                    world-id x y z 4.0)
                    visited (get-in ctx-data [:skill-state :vec-reflection-visited] #{})]
                (doseq [entity entities]
                  (let [entity-id (:uuid entity)
                        difficulty 1.0]
                    (when (and (not= entity-id player-id)
                               (not (contains? visited entity-id)))
                      (when-let [look-vec (and raycast/*raycast*
                                               (raycast/get-player-look-vector raycast/*raycast* player-id))]
                        (let [reflect-speed 1.5
                              vel-x (* (:x look-vec) reflect-speed)
                              vel-y (* (:y look-vec) reflect-speed)
                              vel-z (* (:z look-vec) reflect-speed)]
                          (when player-motion/*player-motion*
                            (player-motion/set-velocity! player-motion/*player-motion*
                                                         entity-id vel-x vel-y vel-z))
                          (ctx/update-context! ctx-id update-in [:skill-state :vec-reflection-visited] conj entity-id)
                          (let [reflect-cost (* difficulty (scaling/lerp 300.0 160.0 exp))]
                            (ps/update-ability-data! player-id
                                                     #(update-in % [:cp-data :cp] - reflect-cost)))
                          (when-let [state (ps/get-player-state player-id)]
                            (let [{:keys [data events]} (learning/add-skill-exp
                                                         (:ability-data state)
                                                         player-id
                                                         :vec-reflection
                                                         (* difficulty 0.0008)
                                                         1.0)]
                              (ps/update-ability-data! player-id (constantly data))
                              (doseq [e events]
                                (ability-evt/fire-ability-event! e))))
                          (log/debug "VecReflection: Reflected entity" entity-id))))))))))))
    (catch Exception e
      (log/warn "VecReflection key-tick failed:" (ex-message e)))))

(defn vec-reflection-on-key-up
  "No-op for toggle skills."
  [{:keys [_player-id _ctx-id]}]
  nil)

(defn vec-reflection-on-key-abort
  "Deactivate on abort."
  [{:keys [ctx-id]}]
  (try
    (toggle/remove-toggle! ctx-id :vec-reflection)
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited)
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-overload-keep)
    (log/debug "VecReflection aborted")
    (catch Exception e
      (log/warn "VecReflection key-abort failed:" (ex-message e)))))

(defn reflect-damage
  "Reflect incoming damage back to attacker when VecReflection is active.
  Returns tuple [performed? reduced-damage]."
  [player-id attacker-id original-damage]
  (try
    (if-let [state (ps/get-player-state player-id)]
      (let [exp (get-skill-exp player-id)
            reflect-multiplier (scaling/lerp 0.6 1.2 exp)
            reflected-damage (* original-damage reflect-multiplier)
            consumption (* original-damage (scaling/lerp 20.0 15.0 exp))
            cp-data (get-in state [:ability-data :cp-data])
            current-cp (:cp cp-data 0.0)]
        (if (>= current-cp consumption)
          (do
            (ps/update-ability-data! player-id
                                     #(update-in % [:cp-data :cp] - consumption))
            (when (and attacker-id entity-damage/*entity-damage*)
              (let [world-id (or (get-in state [:position :world-id])
                                 (get-in (ps/get-player-state attacker-id) [:position :world-id])
                                 "minecraft:overworld")]
              (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                 world-id
                                                 attacker-id
                                                 reflected-damage
                                                 :generic)))
            (let [{:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :vec-reflection
                                         (* original-damage 0.0004)
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e)))
            [true (- original-damage reflected-damage)])
          [false original-damage]))
      [false original-damage])
    (catch Exception e
      (log/warn "VecReflection reflect-damage failed:" (ex-message e))
      [false original-damage])))
