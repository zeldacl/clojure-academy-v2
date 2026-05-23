(ns cn.li.ac.ability.context-runtime-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.content.ability]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.registry :as skill-reg]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.model.resource :as rd]
            [cn.li.ac.ability.model.cooldown :as cd]
            [cn.li.ac.ability.server.service.context-runtime :as rt]
            [cn.li.ac.ability.server.service.cooldown :as cd-svc]))

(defn- reset-test-state! [f]
  (test-contexts/clean-contexts-fixture
   #(test-player/clean-player-states-fixture f)))

(use-fixtures :each reset-test-state!)

(defn- seed-player-state!
  [uuid]
  (let [ability-data (-> (ad/new-ability-data)
                         (assoc :category-id :electromaster)
                         (update :learned-skills conj :arc-gen))
        resource-data (assoc (rd/new-resource-data) :activated true :cur-cp 100.0 :cur-overload 0.0)
        cooldown-data (cd/new-cooldown-data)]
    (ps/set-player-state! uuid {:ability-data ability-data
                                :resource-data resource-data
                                :cooldown-data cooldown-data
                                :preset-data {:active-preset 0 :slots {}}
                                :dirty? false})))

(deftest key-down-blocked-by-cooldown-test
  (let [uuid "test-player-cooldown"
        _ (seed-player-state! uuid)
        _ (ps/update-cooldown-data! uuid cd-svc/set-main-cooldown :arc-gen 10)
        c (ctx/new-server-context uuid :arc-gen "ctx-cd")]
    (ctx/register-context! c)
    (is (false? (rt/handle-key-down! "ctx-cd" {:ctx-id "ctx-cd" :skill-id :arc-gen}))
        "key-down should be rejected while main cooldown is active")
    (is (= ctx/STATUS-TERMINATED (:status (ctx/get-context "ctx-cd")))
        "rejected key-down should terminate context")))

(deftest key-tick-dispatches-while-active-test
  (let [uuid "test-player-resource"
        _ (seed-player-state! uuid)
        c (-> (ctx/new-server-context uuid :arc-gen "ctx-res")
              (assoc :input-state :active))]
    (ctx/register-context! c)
    (is (true? (rt/handle-key-tick! "ctx-res" {:ctx-id "ctx-res" :skill-id :arc-gen}))
        "active context should accept key-tick")
    (is (= ctx/STATUS-ALIVE (:status (ctx/get-context "ctx-res")))
        "key-tick keeps context alive")))

(deftest cooldown-policy-helper-test
  (testing "manual cooldown mode disables auto main cooldown"
    (is (false? (rt/should-apply-main-cooldown? {:cooldown {:mode :manual}}))))
  (testing "missing/other mode uses default auto cooldown"
    (is (true? (rt/should-apply-main-cooldown? {:cooldown {:mode :default}})))
    (is (true? (rt/should-apply-main-cooldown? {})))))

(deftest key-up-termination-policy-helper-test
  (testing "default policy terminates context on key-up"
    (is (true? (rt/should-terminate-context-on-key-up? {}))))
  (testing "skill can opt-out via input-policy"
    (is (false? (rt/should-terminate-context-on-key-up?
                  {:input-policy {:terminate-on-key-up? false}})))
    (is (true? (rt/should-terminate-context-on-key-up?
                 {:input-policy {:terminate-on-key-up? true}})))))

(deftest key-up-can-keep-context-alive-when-policy-disables-termination-test
  (let [uuid "test-player-sticky"
        _ (seed-player-state! uuid)
        c (-> (ctx/new-server-context uuid :arc-gen "ctx-sticky")
              (assoc :input-state :active))]
    (ctx/register-context! c)
    (with-redefs [skill-reg/get-skill (fn [_]
                                        {:id :arc-gen
                                         :ctrl-id :arc-gen
                                         :cooldown {:mode :manual}
                                         :input-policy {:terminate-on-key-up? false}})]
      (is (true? (rt/handle-key-up! "ctx-sticky" {:ctx-id "ctx-sticky" :skill-id :arc-gen}))
          "key-up should be handled")
      (let [updated (ctx/get-context "ctx-sticky")]
        (is (= ctx/STATUS-ALIVE (:status updated))
            "context remains alive when termination policy is disabled")
        (is (= :idle (:input-state updated))
            "input state resets to :idle so subsequent key-down can reactivate")))))
