(ns cn.li.mcmod.platform.entity-motion
  "Protocol for manipulating generic entity motion."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IEntityMotion
  (set-velocity! [this world-id entity-uuid x y z])
  (add-velocity! [this world-id entity-uuid x y z])
  (discard-entity! [this world-id entity-uuid])
  (get-velocity [this world-id entity-uuid]))

(defn install-entity-motion!
  [impl label]
  (when-let [fw-atom fw/*framework*] (swap! fw-atom assoc-in [:platform :entity-motion] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :entity-motion])))
(defn current [] (get-in @(fw/fw-atom) [:platform :entity-motion]))
(defn call-with-runtime [rt f] (f rt))

(defn set-velocity!* [world-id entity-uuid x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-motion])]
    (set-velocity! rt world-id entity-uuid x y z)))
(defn add-velocity!* [world-id entity-uuid x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-motion])]
    (add-velocity! rt world-id entity-uuid x y z)))
(defn discard-entity!* [world-id entity-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-motion])]
    (discard-entity! rt world-id entity-uuid)))
(defn get-velocity* [world-id entity-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-motion])]
    (get-velocity rt world-id entity-uuid)))
