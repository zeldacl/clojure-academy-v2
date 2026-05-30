(ns cn.li.ac.ability.server.effect.state-test
  (:require 
            [cn.li.ac.ability.service.player-state-core :as ps-core]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.state]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(use-fixtures :each
  (fn [f]
    (test-contexts/clean-contexts-fixture
     #(ps-fix/clean-player-states-fixture f))))

(use-fixtures :once
  (fn [f]
    (effect/init-default-ops!)
    (f)))

(def ^:private test-context-owner {:logical-side :server :session-id :test-session})

(deftest assoc-state-op-test
  (let [c (ctx/new-server-context "p" :sk "ctx-st" test-context-owner)]
    (ctx/register-context! c)
    (binding [ctx/*context-owner* test-context-owner]
      (effect/run-op! {:ctx-id "ctx-st" :player-id "p"}
                      [:assoc-state {:k :foo :v 42}])
      (is (= 42 (get-in (ctx/get-context "ctx-st") [:skill-state :foo]))))))

(deftest charge-tick-op-test
  (let [c (ctx/new-server-context "p" :sk "ctx-ch" test-context-owner)]
    (ctx/register-context! c)
    (binding [ctx/*context-owner* test-context-owner]
      (let [out (effect/run-op! {:ctx-id "ctx-ch" :player-id "p"}
                                [:charge-tick {:k :ticks :max 5}])]
        (is (= 1 (:ticks out)))
        (is (= 1 (get-in (ctx/get-context "ctx-ch") [:skill-state :ticks])))))))

(deftest overload-floor-op-test
  (ps-core/get-or-create-player-state! "of-p")
  (effect/run-op! {:player-id "of-p"}
                  [:overload-floor {:floor 10.0}])
  (is (<= 10.0 (get-in (ps-core/get-player-state "of-p") [:resource-data :cur-overload]))))


