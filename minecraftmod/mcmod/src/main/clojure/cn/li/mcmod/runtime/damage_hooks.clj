(ns cn.li.mcmod.runtime.damage-hooks
  "Damage/combat runtime hook surface (compat wrapper)."
  (:require [cn.li.mcmod.runtime.hooks.damage :as damage-hooks]))

(def init-damage-handlers! damage-hooks/init-damage-handlers!)
(def register-damage-handler! damage-hooks/register-damage-handler!)
(def unregister-damage-handler! damage-hooks/unregister-damage-handler!)
(def get-active-damage-handlers damage-hooks/get-active-damage-handlers)
(def process-damage-interception damage-hooks/process-damage-interception)
(def should-cancel-attack-interception? damage-hooks/should-cancel-attack-interception?)
(def compute-aoe-damage damage-hooks/compute-aoe-damage)
(def select-reflection-target damage-hooks/select-reflection-target)
(def compute-reflected-damage damage-hooks/compute-reflected-damage)
(def get-reflection-search-radius damage-hooks/get-reflection-search-radius)
