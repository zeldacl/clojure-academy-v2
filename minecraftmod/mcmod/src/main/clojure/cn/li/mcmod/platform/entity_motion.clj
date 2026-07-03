(ns cn.li.mcmod.platform.entity-motion
  "Generic entity motion operations via Framework function map.

   Impl stored at [:platform :entity-motion]."
  (:require [cn.li.mcmod.framework :as fw]))

(def entity-motion-keys
  #{:set-velocity! :add-velocity! :discard-entity! :get-velocity})

(defn install-entity-motion!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :entity-motion] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :entity-motion])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :entity-motion]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn set-velocity!*    [world-id entity-uuid x y z] (call :set-velocity! world-id entity-uuid x y z))
(defn add-velocity!*    [world-id entity-uuid x y z] (call :add-velocity! world-id entity-uuid x y z))
(defn discard-entity!*  [world-id entity-uuid]       (call :discard-entity! world-id entity-uuid))
(defn get-velocity*     [world-id entity-uuid]       (call :get-velocity world-id entity-uuid))
