(ns cn.li.ac.content.ability.reducer-backed-skill-state-test
  "Samples that skill-state writes go through reducer/command-runtime, not test-only ctx-skill stubs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.effects.state :as state]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (test-contexts/clean-contexts-fixture
    #(test-player/clean-player-states-fixture f)))

(use-fixtures :each reset-fixture)

(def ^:private server-owner {:logical-side :server :session-id :test-session})

(deftest execute-assoc-state-writes-skill-state-via-reducer-test
  (testing "content skill tests should prefer this path over ctx-skill with-redefs when asserting store writes"
    (let [ctx-id "ctx-reducer-backed"
          player-id "p-reducer"
          c (ctx/new-server-context player-id :mag-movement ctx-id server-owner)]
      (test-player/seed-player-state!
        player-id
        {:context-registry {ctx-id {:id ctx-id :skill-id :mag-movement :status :constructed}}})
      (ctx/register-context! c)
      (binding [runtime-hooks/*player-state-owner* test-player/test-player-state-owner
                ctx/*context-owner* server-owner]
        (state/execute-assoc-state! {:ctx-id ctx-id :player-id player-id}
                                    {:k [:charge-ticks] :v 3})
        (is (= 3 (get-in (ctx/get-context ctx-id) [:skill-state :charge-ticks])))
        (let [store-view (store/get-player-state* test-player/test-session-id player-id)]
          (is (= 3 (get-in store-view [:context-registry ctx-id :skill-state :charge-ticks]))))))))

(deftest command-runtime-context-assoc-skill-state-test
  (testing "explicit reducer command updates context-registry skill-state slice"
    (let [ctx-id "ctx-cmd-backed"
          player-id "p-cmd"
          c (ctx/new-server-context player-id :directed-blastwave ctx-id server-owner)]
      (test-player/seed-player-state!
        player-id
        {:context-registry {ctx-id {:id ctx-id :skill-id :directed-blastwave :status :constructed}}})
      (ctx/register-context! c)
      (binding [runtime-hooks/*player-state-owner* test-player/test-player-state-owner
                ctx/*context-owner* server-owner]
        (state/execute-assoc-state! {:ctx-id ctx-id :player-id player-id}
                                    {:k [:performed?] :v true})
        (is (true? (get-in (ctx/get-context ctx-id) [:skill-state :performed?])))
        (is (true? (get-in (store/get-player-state* test-player/test-session-id player-id)
                            [:context-registry ctx-id :skill-state :performed?])))))))
