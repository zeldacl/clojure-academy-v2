(ns cn.li.mcmod.platform.teleportation
  "Protocol for teleportation mechanics."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol ITeleportation
  (teleport-player! [this player-uuid world-id x y z])
  (teleport-with-entities! [this player-uuid world-id x y z radius])
  (reset-fall-damage! [this player-uuid])
  (get-player-position [this player-uuid])
  (get-player-dimension [this player-uuid]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-teleportation!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "teleportation")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* ITeleportation
  [teleport-player!* teleport-player! player-uuid world-id x y z]
  [teleport-with-entities!* teleport-with-entities! player-uuid world-id x y z radius]
  [reset-fall-damage!* reset-fall-damage! player-uuid]
  [get-player-position* get-player-position player-uuid]
  [get-player-dimension* get-player-dimension player-uuid])
