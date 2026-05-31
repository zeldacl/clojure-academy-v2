(ns cn.li.ac.ability.server.effect.damage
  "Server-compat wrapper delegating to canonical effect implementation."
  (:require [cn.li.ac.ability.effects.damage :as damage]))

(def execute-damage-direct! damage/execute-damage-direct!)
(def execute-damage-aoe! damage/execute-damage-aoe!)