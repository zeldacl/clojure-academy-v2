(ns cn.li.mcmod.platform.entity-damage
  "Entity damage operations via Framework function map.

   Replaces defprotocol IEntityDamage + ^:dynamic *entity-damage* with
   plain function map stored at [:platform :entity-damage]."
  (:require [cn.li.mcmod.framework :as fw]))

(def entity-damage-keys
  "Expected keys in the entity-damage function map."
  #{:apply-direct-damage! :apply-aoe-damage! :apply-reflection-damage!})

(defn install-entity-damage!
  "Install entity damage implementation map into Framework."
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :entity-damage] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :entity-damage])))
(defn current  [] (get-in @(fw/fw-atom) [:platform :entity-damage]))

(defn call-with-runtime [rt f] (f rt))

(defn apply-direct-damage!* [world-id entity-uuid damage source-type]
  (when-let [f (get-in @(fw/fw-atom) [:platform :entity-damage :apply-direct-damage!])]
    (f world-id entity-uuid damage source-type)))
(defn apply-aoe-damage!* [world-id x y z radius damage source-type falloff?]
  (when-let [f (get-in @(fw/fw-atom) [:platform :entity-damage :apply-aoe-damage!])]
    (f world-id x y z radius damage source-type falloff?)))
(defn apply-reflection-damage!* [world-id entity-uuid damage source-type reflection-count max-reflections]
  (when-let [f (get-in @(fw/fw-atom) [:platform :entity-damage :apply-reflection-damage!])]
    (f world-id entity-uuid damage source-type reflection-count max-reflections)))
