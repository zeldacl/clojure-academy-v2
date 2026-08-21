(ns cn.li.ac.ability.service.combat-runtime-bridge
  "AC bridge for the Combat Core EDN runtime.

   No EDN parsing, component expansion or VM execution belongs here."
  (:require [cn.li.combat.skill-runtime :as runtime]
            [cn.li.ac.ability.service.combat-catalog :as catalog]))

(defn execute! [ability-id owner intent]
  (runtime/execute! (catalog/catalog) ability-id owner intent))

(defn commit-actions! [owner actions action-handlers]
  (runtime/commit-actions! owner actions action-handlers))
