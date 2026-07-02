(ns cn.li.mcmod.platform.entity-damage
  "Protocol for entity damage application."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IEntityDamage
  (apply-direct-damage! [this world-id entity-uuid damage source-type])
  (apply-aoe-damage! [this world-id x y z radius damage source-type falloff?])
  (apply-reflection-damage! [this world-id entity-uuid damage source-type reflection-count max-reflections]))

(defn install-entity-damage!
  [impl label]
  (when-let [fw-atom fw/*framework*] (swap! fw-atom assoc-in [:platform :entity-damage] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :entity-damage])))
(defn current [] (get-in @(fw/fw-atom) [:platform :entity-damage]))
(defn call-with-runtime [rt f] (f rt))

(defn apply-direct-damage!* [world-id entity-uuid damage source-type]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-damage])]
    (apply-direct-damage! rt world-id entity-uuid damage source-type)))
(defn apply-aoe-damage!* [world-id x y z radius damage source-type falloff?]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-damage])]
    (apply-aoe-damage! rt world-id x y z radius damage source-type falloff?)))
(defn apply-reflection-damage!* [world-id entity-uuid damage source-type reflection-count max-reflections]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :entity-damage])]
    (apply-reflection-damage! rt world-id entity-uuid damage source-type reflection-count max-reflections)))
