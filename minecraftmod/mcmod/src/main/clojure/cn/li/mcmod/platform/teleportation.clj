(ns cn.li.mcmod.platform.teleportation
  "Teleportation operations via Framework function map.

   Impl stored at [:platform :teleportation]."
  (:require [cn.li.mcmod.framework :as fw]))

(def teleportation-keys
  #{:teleport-player! :teleport-with-entities! :reset-fall-damage!
    :get-player-position :get-player-dimension})

(defn install-teleportation!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :teleportation] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :teleportation])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :teleportation]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn teleport-player!*         [player-uuid world-id x y z]      (call :teleport-player! player-uuid world-id x y z))
(defn teleport-with-entities!*  [player-uuid world-id x y z r]    (call :teleport-with-entities! player-uuid world-id x y z r))
(defn reset-fall-damage!*       [player-uuid]                     (call :reset-fall-damage! player-uuid))
(defn get-player-position*      [player-uuid]                     (call :get-player-position player-uuid))
(defn get-player-dimension*     [player-uuid]                     (call :get-player-dimension player-uuid))
