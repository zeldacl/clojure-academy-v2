(ns cn.li.ac.ability.effects.motion
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(defn player-motion-available?
  []
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/get-adapter fw-atom :player-motion))))

(defn set-player-velocity!
  [player-id x y z]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :player-motion :set-velocity!
                            player-id x y z))))

(defn add-player-velocity!
  [player-id x y z]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :player-motion :add-velocity!
                            player-id x y z))))

(defn player-velocity
  [player-id]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :player-motion :get-velocity player-id)))

(defn set-player-on-ground!
  [player-id on-ground?]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :player-motion :set-on-ground!
                            player-id on-ground?))))

(defn player-on-ground?
  [player-id]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :player-motion :is-on-ground? player-id))))

(defn dismount-riding!
  [player-id]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :player-motion :dismount-riding! player-id))))

(defn execute-set-player-velocity!
  [evt {:keys [x y z]}]
  (when (player-motion-available?)
    (set-player-velocity!
                                 (:player-id evt)
                                 (double (or x 0.0))
                                 (double (or y 0.0))
                                 (double (or z 0.0))))
  evt)

(defn execute-add-entity-velocity!
  [evt {:keys [target x y z]}]
  (when (entity-motion/available?)
    (let [uuid (or (when (map? target) (:uuid target))
                   (get evt target)
                   target)]
      (when (string? uuid)
        (entity-motion/add-velocity!*
                                     (:world-id evt)
                                     uuid
                                     (double (or x 0.0))
                                     (double (or y 0.0))
                                     (double (or z 0.0))))))
  evt)

(defn execute-reset-fall-damage!
  [evt _params]
  (when (teleportation/available?)
    (teleportation/reset-fall-damage!* (:player-id evt)))
  evt)

