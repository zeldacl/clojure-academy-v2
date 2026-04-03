(ns cn.li.ac.ability.context-runtime-test
  (:require [cn.li.ac.content.ability]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.model.ability-data :as ad]
            [cn.li.ac.ability.model.resource-data :as rd]
            [cn.li.ac.ability.model.cooldown-data :as cd]
            [cn.li.ac.ability.service.context-runtime :as rt]
            [cn.li.ac.ability.service.cooldown :as cd-svc]))

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

(defn test-key-down-blocked-by-cooldown
  []
  (let [uuid "test-player-cooldown"
        _ (seed-player-state! uuid)
        _ (ps/update-cooldown-data! uuid cd-svc/set-main-cooldown :arc-gen 10)
        c (ctx/new-server-context uuid :arc-gen "ctx-cd")]
    (ctx/register-context! c)
    (assert (false? (rt/handle-key-down! "ctx-cd" {:ctx-id "ctx-cd" :skill-id :arc-gen}))
            "key-down should be rejected while main cooldown is active")
    (assert (= ctx/STATUS-TERMINATED (:status (ctx/get-context "ctx-cd")))
            "rejected key-down should terminate context")))

(defn test-key-tick-aborts-on-resource-insufficient
  []
  (let [uuid "test-player-resource"
        _ (seed-player-state! uuid)
        _ (ps/update-resource-data! uuid assoc :cur-cp 0.0)
        c (-> (ctx/new-server-context uuid :arc-gen "ctx-res")
              (assoc :input-state :active))]
    (ctx/register-context! c)
    (assert (false? (rt/handle-key-tick! "ctx-res" {:ctx-id "ctx-res" :skill-id :arc-gen}))
            "key-tick should fail when resource consumption cannot pass")
    (assert (= ctx/STATUS-TERMINATED (:status (ctx/get-context "ctx-res")))
            "failed key-tick should terminate context")))

(defn run-all-tests []
  (println "=== ability context runtime tests ===")
  (test-key-down-blocked-by-cooldown)
  (test-key-tick-aborts-on-resource-insufficient)
  (println "ok"))
