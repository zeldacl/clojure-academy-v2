(ns cn.li.ac.ability.effect.motion
  (:require [cn.li.ac.ability.effect :as effect]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(effect/defop :set-player-velocity
  [evt {:keys [x y z]}]
  (when player-motion/*player-motion*
    (player-motion/set-velocity! player-motion/*player-motion*
                                 (:player-id evt)
                                 (double (or x 0.0))
                                 (double (or y 0.0))
                                 (double (or z 0.0))))
  evt)

(effect/defop :add-entity-velocity
  [evt {:keys [target x y z]}]
  (let [_target target
        _x x
        _y y
        _z z]
    ;; Placeholder op: keep stable while finalizing IEntityMotion protocol.
    nil)
  evt)

(effect/defop :reset-fall-damage
  [evt _]
  (when teleportation/*teleportation*
    (teleportation/reset-fall-damage! teleportation/*teleportation* (:player-id evt)))
  evt)
