(ns cn.li.ac.ability.adapters.runtime-bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.adapters.runtime-bridge :as runtime-bridge]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest install-runtime-hooks-connects-player-state-and-sync-payload-test
  (runtime-bridge/install-runtime-hooks!)
  (let [uuid "hook-player"
        state (-> (ps/fresh-state)
                  (assoc-in [:ability-data :category-id] :electromaster)
                  (assoc-in [:resource-data :activated] true)
                  (assoc-in [:preset-data :active-preset] 1)
                  (assoc-in [:develop-data :level-progress] 42.0)
                  (assoc-in [:terminal-data :installed-apps] #{:tutorial :settings}))]
    (runtime-hooks/set-player-state! uuid state)
    (is (= state (runtime-hooks/get-player-state uuid)))
    (is (= {:uuid uuid
            :ability-data (:ability-data state)
            :resource-data (:resource-data state)
            :cooldown-data (:cooldown-data state)
            :preset-data (:preset-data state)
            :develop-data (:develop-data state)
            :terminal-data (:terminal-data state)}
           (runtime-hooks/build-sync-payload uuid)))
    (runtime-hooks/mark-player-clean! uuid)
    (is (false? (ps/dirty? uuid)))))

(deftest dimension-change-hook-aborts-player-contexts-test
  (runtime-bridge/install-runtime-hooks!)
  (let [aborted (atom [])]
    (with-redefs [ctx-mgr/abort-player-contexts! (fn [player-uuid]
                                                   (swap! aborted conj player-uuid))]
      (runtime-hooks/on-player-dimension-change! "dimension-player" "minecraft:overworld" "minecraft:the_nether")
      (is (= ["dimension-player"] @aborted)))))