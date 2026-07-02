(ns cn.li.mcmod.platform.player-motion
  "Protocol for manipulating player motion and physics."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IPlayerMotion
  (set-velocity! [this player-id x y z])
  (add-velocity! [this player-id x y z])
  (get-velocity [this player-id])
  (set-on-ground! [this player-id on-ground?])
  (is-on-ground? [this player-id])
  (dismount-riding! [this player-id]))

(defn install-player-motion!
  [impl label]
  (when-let [fw-atom fw/*framework*] (swap! fw-atom assoc-in [:platform :player-motion] impl)) nil)

(defn available? [] (boolean (get-in @fw/*framework* [:platform :player-motion])))
(defn current [] (get-in @fw/*framework* [:platform :player-motion]))
(defn call-with-runtime [rt f] (f rt))

(defn set-velocity!* [player-id x y z]
  (when-let [rt (get-in @fw/*framework* [:platform :player-motion])]
    (set-velocity! rt player-id x y z)))
(defn add-velocity!* [player-id x y z]
  (when-let [rt (get-in @fw/*framework* [:platform :player-motion])]
    (add-velocity! rt player-id x y z)))
(defn get-velocity* [player-id]
  (when-let [rt (get-in @fw/*framework* [:platform :player-motion])]
    (get-velocity rt player-id)))
(defn set-on-ground!* [player-id on-ground?]
  (when-let [rt (get-in @fw/*framework* [:platform :player-motion])]
    (set-on-ground! rt player-id on-ground?)))
(defn is-on-ground?* [player-id]
  (when-let [rt (get-in @fw/*framework* [:platform :player-motion])]
    (is-on-ground? rt player-id)))
(defn dismount-riding!* [player-id]
  (when-let [rt (get-in @fw/*framework* [:platform :player-motion])]
    (dismount-riding! rt player-id)))
