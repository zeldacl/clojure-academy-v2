(ns cn.li.ac.ability.server.effect.potion
  "Server-compat wrapper delegating to canonical effect implementation."
  (:require [cn.li.ac.ability.effects.potion :as potion]))

(def execute-potion! potion/execute-potion!)
(def execute-potion-roll! potion/execute-potion-roll!)