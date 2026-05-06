(ns cn.li.ac.test-runner
  "Loads all ac unit test namespaces and runs clojure.test (used by :ac:runAcClojureTests)."
  (:require [clojure.test :as t])
  (:gen-class))

(def ^:private test-namespaces
  '[cn.li.ac.ability.ability-model-test
    cn.li.ac.ability.context-runtime-test
    cn.li.ac.ability.cooldown-model-test
    cn.li.ac.ability.develop-model-test
    cn.li.ac.ability.develop-service-test
    cn.li.ac.ability.learning-service-test
    cn.li.ac.ability.learning-test
    cn.li.ac.ability.item-actions-test
    cn.li.ac.ability.preset-model-test
    cn.li.ac.ability.resource-model-test
    cn.li.ac.ability.resource-test
    cn.li.ac.ability.util.balance-test
    cn.li.ac.ability.server.service.context-mgr-test
    cn.li.ac.block.wireless-node-test
    cn.li.ac.achievement.dispatcher-test
    cn.li.ac.command.dsl-test
    cn.li.ac.command.handlers-test
    cn.li.ac.config.gameplay-test
    cn.li.ac.config.registry-test
    cn.li.ac.content.ability.meltdowner.damage-helper-test
    cn.li.ac.content.ability.teleporter.tp-skill-helper-test
    cn.li.ac.energy.operations-test
    cn.li.ac.gui.cgui-regression-verify
    cn.li.ac.gui.slot-validators-test
    cn.li.ac.item.test-battery-test
    cn.li.ac.recipe.crafting-recipes-test
    cn.li.ac.terminal.player-data-test
    cn.li.ac.wireless.api-event-regression-test
    cn.li.ac.wireless.data.network-config-test
    cn.li.ac.wireless.gui.messages-dsl-test
    cn.li.ac.wireless.search-config-test])

(defn -main [& _]
  (doseq [ns-sym test-namespaces]
    (require ns-sym))
  (let [summary (apply t/run-tests test-namespaces)]
    (shutdown-agents)
    (let [fail (:fail summary 0)
          err (:error summary 0)]
      (when (or (pos? fail) (pos? err))
        (binding [*out* *err*]
          (println "Clojure test summary:" summary)))
      (System/exit (if (and (zero? fail) (zero? err)) 0 1)))))
