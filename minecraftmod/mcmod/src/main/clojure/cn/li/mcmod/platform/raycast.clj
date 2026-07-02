(ns cn.li.mcmod.platform.raycast
  "Protocol for raycasting (line-of-sight queries).

  Platform (forge/fabric) installs an IRaycast implementation via install-raycast!.
  Game logic (ac) calls *-suffixed wrappers without accessing the runtime var."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IRaycast
  "Raycasting utilities for line-of-sight queries."

  (raycast-blocks [this world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
    "Raycast from start position in direction to find first block hit.
    Returns block hit map or nil.")

  (raycast-entities [this world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
    "Raycast from start position in direction to find first entity hit.")

  (raycast-combined [this world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
    "Raycast from start position in direction to find first hit (block or entity).")

  (get-player-look-vector [this player-uuid]
    "Get player's current look direction vector.")

  (raycast-from-player [this player-uuid max-distance living-only?]
    "Raycast from player's eye position in look direction."))

(defn install-raycast!
  [impl label]
  (when-let [fw-atom fw/*framework*] (swap! fw-atom assoc-in [:platform :raycast] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :raycast])))
(defn current [] (get-in @(fw/fw-atom) [:platform :raycast]))
(defn call-with-runtime [rt f] (f rt))

(defn raycast-blocks* [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :raycast])]
    (raycast-blocks rt world-id start-x start-y start-z dir-x dir-y dir-z max-distance)))
(defn raycast-entities* [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :raycast])]
    (raycast-entities rt world-id start-x start-y start-z dir-x dir-y dir-z max-distance)))
(defn raycast-combined* [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :raycast])]
    (raycast-combined rt world-id start-x start-y start-z dir-x dir-y dir-z max-distance)))
(defn get-player-look-vector* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :raycast])]
    (get-player-look-vector rt player-uuid)))
(defn raycast-from-player* [player-uuid max-distance living-only?]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :raycast])]
    (raycast-from-player rt player-uuid max-distance living-only?)))
