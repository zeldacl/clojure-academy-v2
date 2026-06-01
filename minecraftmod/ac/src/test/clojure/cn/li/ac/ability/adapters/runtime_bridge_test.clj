(ns cn.li.ac.ability.adapters.runtime-bridge-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.adapters.runtime-bridge :as runtime-bridge]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(defn- reset-runtime-bridge-fixture [f]
  (when-let [guard-var (ns-resolve 'cn.li.ac.ability.adapters.runtime-bridge 'runtime-hooks-installed?)]
    (reset! (var-get guard-var) false))
  (binding [runtime-hooks/*player-state-owner* {:server-session-id :test-session}
            runtime-hooks/*client-session-id* :test-client-session]
    (f)))

(use-fixtures :each reset-runtime-bridge-fixture)

(deftest install-runtime-hooks-connects-player-state-and-sync-payload-test
  (runtime-bridge/install-runtime-hooks!)
  (let [uuid "hook-player"
  state (-> (store/fresh-player-state)
                  (assoc-in [:ability-data :category-id] :electromaster)
                  (assoc-in [:resource-data :activated] true)
                  (assoc-in [:preset-data :active-preset] 1)
                  (assoc-in [:develop-data :level-progress] 42.0)
                  (assoc-in [:terminal-data :installed-apps] #{:tutorial :settings}))]
    (runtime-hooks/sync-player-state! uuid state)
    (is (= state (runtime-hooks/get-player-state uuid)))
    (is (true? (runtime-hooks/runtime-activated? uuid)))
    (is (= {:uuid uuid
            :ability-data (:ability-data state)
            :resource-data (:resource-data state)
            :cooldown-data (:cooldown-data state)
            :preset-data (:preset-data state)
            :develop-data (:develop-data state)
            :terminal-data (:terminal-data state)}
           (runtime-hooks/build-sync-payload uuid)))
    (is (nil? (runtime-hooks/mark-player-clean! uuid)))))

(deftest dimension-change-hook-aborts-player-contexts-test
  (runtime-bridge/install-runtime-hooks!)
  (let [aborted (atom [])]
    (with-redefs [ctx-mgr/abort-player-contexts! (fn [player-uuid]
                                                   (swap! aborted conj player-uuid))]
      (runtime-hooks/on-player-dimension-change! "dimension-player" "minecraft:overworld" "minecraft:the_nether")
      (is (= ["dimension-player"] @aborted)))))

(deftest runtime-client-charge-coin-visual-state-contract-test
  (runtime-bridge/install-runtime-hooks!)
  (let [state (binding [runtime-hooks/*client-session-id* :test-client-session]
                (runtime-hooks/client-visual-state :ac/charge-coin {:player-uuid "client-player"}))]
    (is (contains? state :active?))
    (is (contains? state :coin-active?))
    (is (contains? state :charge-ratio))
    (is (contains? state :charge-ticks))
    (is (contains? state :coin-progress))))


