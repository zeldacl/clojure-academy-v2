(ns cn.li.ac.ability.server.effect.world
  (:require [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(effect/defop :spawn-entity-from-player
  [evt {:keys [entity-id speed]}]
  (when-let [player (:player evt)]
    (entity/player-spawn-entity-by-id! player
                                       (str entity-id)
                                       (double (or speed 0.0))))
  evt)

(effect/defop :spawn-lightning
  [evt {:keys [at]}]
  (when world-effects/*world-effects*
    (let [{:keys [x y z]} (or (when (map? at) at) (get evt at))]
      (world-effects/spawn-lightning! world-effects/*world-effects*
                                      (:world-id evt)
                                      (double x) (double y) (double z))))
  evt)

(effect/defop :create-explosion
  [evt {:keys [at radius fire?]}]
  (when world-effects/*world-effects*
    (let [{:keys [x y z]} (or (when (map? at) at) (get evt at))]
      (world-effects/create-explosion! world-effects/*world-effects*
                                       (:world-id evt)
                                       (double x) (double y) (double z)
                                       (double (or radius 4.0))
                                       (boolean fire?))))
  evt)
