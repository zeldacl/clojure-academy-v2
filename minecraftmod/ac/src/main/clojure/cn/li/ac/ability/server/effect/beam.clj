(ns cn.li.ac.ability.server.effect.beam
  "Server-compat wrapper delegating to canonical effect implementation."
  (:require [cn.li.ac.ability.effects.beam :as beam]))

(def execute-beam! beam/execute-beam!)