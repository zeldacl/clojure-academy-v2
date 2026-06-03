(ns cn.li.mcmod.platform.player-motion
  "Protocol for manipulating player motion and physics."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IPlayerMotion
  (set-velocity! [this player-id x y z])
  (add-velocity! [this player-id x y z])
  (get-velocity [this player-id])
  (set-on-ground! [this player-id on-ground?])
  (is-on-ground? [this player-id])
  (dismount-riding! [this player-id]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-player-motion!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "player-motion")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IPlayerMotion
  [set-velocity!* set-velocity! player-id x y z]
  [add-velocity!* add-velocity! player-id x y z]
  [get-velocity* get-velocity player-id]
  [set-on-ground!* set-on-ground! player-id on-ground?]
  [is-on-ground?* is-on-ground? player-id]
  [dismount-riding!* dismount-riding! player-id])
