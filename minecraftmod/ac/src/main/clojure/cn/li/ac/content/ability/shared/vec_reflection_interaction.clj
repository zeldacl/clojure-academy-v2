(ns cn.li.ac.content.ability.shared.vec-reflection-interaction
  "Shared vec-reflection beam interaction for railgun / meltdowner.

  No Minecraft imports."
  (:require [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.util.toggle :as toggle]))

(defn- reflection-cp-consumption
  [target-player-uuid incoming-damage caster-skill-id cp-field-id]
  (let [exp (skill-effects/skill-exp target-player-uuid :vec-reflection)]
    (* (double incoming-damage)
       (skill-config/lerp-double caster-skill-id cp-field-id exp))))

(defn- can-afford-reflection?
  [target-player-uuid consumption]
  (when-let [state (skill-effects/get-player-state target-player-uuid)]
    (>= (double (get-in state [:resource-data :cur-cp] 0.0))
        (double consumption))))

(defn- consume-reflection-cp!
  [target-player-uuid consumption]
  (skill-effects/perform-resource! target-player-uuid 0.0 (double consumption) false))

(defn build-reflection-callbacks
  "Build :reflect-can-fn / :reflect-shot-fn for effect.beam/execute-beam!

  Options:
    :ctx-id            context id for FX
    :caster-skill-id   skill config namespace for CP cost field (e.g. :railgun)
    :cp-field-id       config field id (e.g. :reflection.cp-consumption-per-damage)
    :reflect-shot-fn   (fn [ctx-id reflector-player-uuid] -> truthy when hit)"
  [{:keys [ctx-id caster-skill-id cp-field-id reflect-shot-fn]}]
  {:reflect-can-fn
   (fn [target-player-uuid incoming-damage]
     (when (toggle/toggle-active-for-player? target-player-uuid :vec-reflection)
       (let [consumption (reflection-cp-consumption target-player-uuid
                                                      incoming-damage
                                                      caster-skill-id
                                                      cp-field-id)]
         (can-afford-reflection? target-player-uuid consumption))))

   :reflect-shot-fn
   (fn [target-player-uuid incoming-damage]
     (let [consumption (reflection-cp-consumption target-player-uuid
                                                  incoming-damage
                                                  caster-skill-id
                                                  cp-field-id)]
       (when (can-afford-reflection? target-player-uuid consumption)
         (consume-reflection-cp! target-player-uuid consumption)
         (reflect-shot-fn ctx-id target-player-uuid))))})
