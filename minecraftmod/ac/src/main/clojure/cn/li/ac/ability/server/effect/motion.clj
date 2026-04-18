(ns cn.li.ac.ability.server.effect.motion
  (:require [cn.li.ac.ability.server.effect.core :as effect]
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
  (when entity-motion/*entity-motion*
    (let [uuid (or (when (map? target) (:uuid target))
                   (get evt target)
                   target)]
      (when (string? uuid)
        (entity-motion/add-velocity! entity-motion/*entity-motion*
                                     (:world-id evt)
                                     uuid
                                     (double (or x 0.0))
                                     (double (or y 0.0))
                                     (double (or z 0.0))))))
  evt)

(effect/defop :reset-fall-damage
  [evt _]
  (when teleportation/*teleportation*
    (teleportation/reset-fall-damage! teleportation/*teleportation* (:player-id evt)))
  evt)
