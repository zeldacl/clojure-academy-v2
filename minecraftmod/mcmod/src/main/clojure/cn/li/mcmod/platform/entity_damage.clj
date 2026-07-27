(ns cn.li.mcmod.platform.entity-damage
  (:require [cn.li.mcmod.framework :as fw]))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :entity-damage])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :entity-damage]))

(defn- call
  [k & args]
  (let [ops (current)
        f (get ops k)]
    (when-not f
      (throw (ex-info "Required entity-damage operation is not installed"
                      {:operation k :installed (keys ops)})))
    (apply f args)))

(defn install-pvp-gate!
  [pred]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in [:platform :entity-damage-pvp-gate] pred))
  nil)

(defn pvp-allowed?
  []
  (if-let [pred (get-in @(fw/fw-atom) [:platform :entity-damage-pvp-gate])]
    (boolean (pred))
    true))

(defn apply-direct-damage!
  "opts (optional) supports :reset-invulnerable-time? — when true, clears the
  target's post-hit invulnerability window before applying damage, for
  skills whose original deliberately does this (e.g. ElectronMissile's
  hurtResistantTime = -1) so rapid successive hits always land full damage."
  ([world-id entity-uuid damage source-type]
   (apply-direct-damage! world-id entity-uuid damage source-type nil))
  ([world-id entity-uuid damage source-type opts]
   (call :apply-direct-damage! world-id entity-uuid damage source-type opts)))

(defn apply-aoe-damage!
  [world-id x y z radius damage source-type falloff?]
  (call :apply-aoe-damage! world-id x y z radius damage source-type falloff?))

(defn apply-reflection-damage!
  [world-id entity-uuid damage source-type reflection-count max-reflections]
  (call :apply-reflection-damage! world-id entity-uuid damage source-type reflection-count max-reflections))
