(ns cn.li.ac.ability.server.effect.damage
  (:require [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]))

(effect/defop :damage-direct
  [evt {:keys [target amount damage-type]}]
  (when-let [uuid (or (when (map? target) (:uuid target))
                      (get evt target))]
    (when entity-damage/*entity-damage*
      (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                          (:world-id evt)
                                          uuid
                                          (double (or amount 0.0))
                                          (or damage-type :generic))))
  evt)

(effect/defop :damage-aoe
  [evt {:keys [center radius amount damage-type exclude]}]
  (when (and world-effects/*world-effects* entity-damage/*entity-damage*)
    (let [center* (or (when (map? center) center) (get evt center))
          world-id (:world-id evt)
          victims (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                         world-id
                                                         (double (:x center*))
                                                         (double (:y center*))
                                                         (double (:z center*))
                                                         (double radius))
          excluded (set (concat [(:player-id evt)] (or exclude [])))]
      (doseq [{:keys [uuid x y z]} victims]
        (when-not (contains? excluded uuid)
          (let [dx (- (double x) (double (:x center*)))
                dy (- (double y) (double (:y center*)))
                dz (- (double z) (double (:z center*)))
                dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                final (* (double amount) (bal/falloff-linear dist radius))]
            (when (> final 0.0)
              (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                  world-id uuid final
                                                  (or damage-type :generic))))))))
  evt)
