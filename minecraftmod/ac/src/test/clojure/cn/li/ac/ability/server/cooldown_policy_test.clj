(ns cn.li.ac.ability.server.cooldown-policy-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]            [cn.li.ac.ability.model.cooldown :as cd]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill :as skill-reg]
            [cn.li.ac.ability.service.context-state :as rt]))

(defn- reset-test-state! [f]
  (test-contexts/clean-contexts-fixture
   #(test-player/clean-player-states-fixture f)))

(use-fixtures :each reset-test-state!)

(def ^:private test-context-owner {:logical-side :server :session-id :test-session})

(defn- seed-player!
  ([uuid]
   (seed-player! uuid (cd/new-cooldown-data)))
  ([uuid cooldown-data]
  (store/set-player-state!* test-player/test-session-id
                            uuid
                            {:ability-data {:skill-exps {:arc-gen 0.0}}
                             :cooldown-data cooldown-data})))

(defn- active-context!
  [uuid ctx-id]
  (ctx/register-context!
    (assoc (ctx/new-server-context uuid :arc-gen ctx-id test-context-owner)
          :input-state :active)))

(deftest default-key-up-applies-main-cooldown-with-max-existing-rule-test
  (let [uuid "cooldown-player-default"
        ctx-id "ctx-cooldown-default"
        existing (cd/set-cooldown (cd/new-cooldown-data) :arc-gen :main 20)]
    (seed-player! uuid existing)
    (active-context! uuid ctx-id)
    (with-redefs [skill-reg/get-skill (fn [_]
                                        {:id :arc-gen
                                         :ctrl-id :arc-gen
                                         :cooldown {:mode :default}
                                         :cooldown-ticks 7})
                  evt/fire-ability-event! (fn [_] nil)]
      (binding [ctx/*context-owner* test-context-owner]
        (is (true? (rt/handle-key-up! ctx-id {:ctx-id ctx-id :skill-id :arc-gen})))
        (is (= 20 (cd/get-remaining (:cooldown-data (store/get-player-state* test-player/test-session-id uuid)) :arc-gen :main)))
        (is (= ctx/STATUS-TERMINATED (:status (ctx/get-context ctx-id))))))))

(deftest manual-key-up-does-not-apply-automatic-main-cooldown-test
  (let [uuid "cooldown-player-manual"
        ctx-id "ctx-cooldown-manual"]
    (seed-player! uuid)
    (active-context! uuid ctx-id)
    (with-redefs [skill-reg/get-skill (fn [_]
                                        {:id :arc-gen
                                         :ctrl-id :arc-gen
                                         :cooldown {:mode :manual}
                                         :cooldown-ticks 9})
                  evt/fire-ability-event! (fn [_] nil)]
      (binding [ctx/*context-owner* test-context-owner]
        (is (true? (rt/handle-key-up! ctx-id {:ctx-id ctx-id :skill-id :arc-gen})))
        (is (= 0 (cd/get-remaining (:cooldown-data (store/get-player-state* test-player/test-session-id uuid)) :arc-gen :main)))
        (is (= ctx/STATUS-TERMINATED (:status (ctx/get-context ctx-id))))))))

