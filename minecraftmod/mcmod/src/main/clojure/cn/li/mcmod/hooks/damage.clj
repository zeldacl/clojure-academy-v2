(ns cn.li.mcmod.hooks.damage
  "Damage/combat hook surface (delegates to hooks-core during migration)."
  (:require [cn.li.mcmod.hooks.core :as hooks-core]))

(def init-damage-handlers! hooks-core/init-damage-handlers!)
(def register-damage-handler! hooks-core/register-damage-handler!)
(def unregister-damage-handler! hooks-core/unregister-damage-handler!)
(def get-active-damage-handlers hooks-core/get-active-damage-handlers)
(def process-damage-interception hooks-core/process-damage-interception)
(def should-cancel-attack-interception? hooks-core/should-cancel-attack-interception?)
(def compute-aoe-damage hooks-core/compute-aoe-damage)
(def select-reflection-target hooks-core/select-reflection-target)
(def compute-reflected-damage hooks-core/compute-reflected-damage)
(def get-reflection-search-radius hooks-core/get-reflection-search-radius)
