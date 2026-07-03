(ns cn.li.mcmod.platform.player-motion
  "Player motion and physics operations via Framework function map.

   Impl stored at [:platform :player-motion]."
  (:require [cn.li.mcmod.framework :as fw]))

(def player-motion-keys
  #{:set-velocity! :add-velocity! :get-velocity
    :set-on-ground! :is-on-ground? :dismount-riding!})

(defn install-player-motion!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :player-motion] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :player-motion])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :player-motion]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn set-velocity!*    [player-id x y z]         (call :set-velocity! player-id x y z))
(defn add-velocity!*    [player-id x y z]         (call :add-velocity! player-id x y z))
(defn get-velocity*     [player-id]               (call :get-velocity player-id))
(defn set-on-ground!*   [player-id on-ground?]    (call :set-on-ground! player-id on-ground?))
(defn is-on-ground?*    [player-id]               (call :is-on-ground? player-id))
(defn dismount-riding!* [player-id]               (call :dismount-riding! player-id))
