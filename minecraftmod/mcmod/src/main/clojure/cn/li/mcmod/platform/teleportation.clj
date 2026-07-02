(ns cn.li.mcmod.platform.teleportation
  "Protocol for teleportation mechanics."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol ITeleportation
  (teleport-player! [this player-uuid world-id x y z])
  (teleport-with-entities! [this player-uuid world-id x y z radius])
  (reset-fall-damage! [this player-uuid])
  (get-player-position [this player-uuid])
  (get-player-dimension [this player-uuid]))

(defn install-teleportation!
  [impl label]
  (when-let [fw-atom fw/*framework*] (swap! fw-atom assoc-in [:platform :teleportation] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :teleportation])))
(defn current [] (get-in @(fw/fw-atom) [:platform :teleportation]))
(defn call-with-runtime [rt f] (f rt))

(defn teleport-player!* [player-uuid world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :teleportation])]
    (teleport-player! rt player-uuid world-id x y z)))
(defn teleport-with-entities!* [player-uuid world-id x y z radius]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :teleportation])]
    (teleport-with-entities! rt player-uuid world-id x y z radius)))
(defn reset-fall-damage!* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :teleportation])]
    (reset-fall-damage! rt player-uuid)))
(defn get-player-position* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :teleportation])]
    (get-player-position rt player-uuid)))
(defn get-player-dimension* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :teleportation])]
    (get-player-dimension rt player-uuid)))
