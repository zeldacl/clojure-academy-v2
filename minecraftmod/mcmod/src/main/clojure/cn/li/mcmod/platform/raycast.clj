(ns cn.li.mcmod.platform.raycast
  "Protocol for raycasting (line-of-sight queries).

  Platform (forge/fabric) installs an IRaycast implementation via install-raycast!.
  Game logic (ac) calls *-suffixed wrappers without accessing the runtime var."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

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

(def ^:private ^:dynamic *runtime* nil)

(defn install-raycast!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "raycast")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IRaycast
  [raycast-blocks* raycast-blocks world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  [raycast-entities* raycast-entities world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  [raycast-combined* raycast-combined world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  [get-player-look-vector* get-player-look-vector player-uuid]
  [raycast-from-player* raycast-from-player player-uuid max-distance living-only?])
