(ns cn.li.ac.ability.service.edn-catalog-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.edn-catalog :as catalog]
            [cn.li.ac.ability.service.edn-execution :as execution]
            [cn.li.ac.ability.service.edn-sessions :as sessions])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram]))

(deftest first-phase-catalog-is-authoritative
  (let [state (catalog/initialize!)]
    (is (true? (:initialized? state)))
    (is (catalog/available? :railgun))
    (is (not (catalog/available? :thunder-bolt)))
    (is (= :pending (catalog/migration-status :thunder-bolt)))
    (is (= :railgun (get-in state [:combat :abilities :railgun :id])))
    (is (= :railgun-beam (get-in state [:vfx :effects :railgun-beam :id])))))

(deftest migrated-entry-executes-compiled-program
  (catalog/initialize!)
  (let [compiled (get-in (catalog/catalog) [:combat :abilities :railgun])
        _ (do
            (is (pos? (count (:compiled-ir compiled))))
            (is (pos? (alength ^objects (.-objectConstants ^CompiledProgram (:compiled-program compiled))))))
        result (execution/execute! :railgun "owner-1" {:action :start})]
    (is (= :accepted (:status result)))
    (is (= :railgun (:ability-id result)))
    (is (= :started (:outcome result)))))

(deftest session-index-is-neutral-and-tickable
  (catalog/initialize!)
  (sessions/reset-for-test!)
  (let [entry (sessions/start! "owner-1" :railgun
                               {:context {:world-id "world"}
                                :parameter-snapshot {:charge-ticks 20}
                                :activation-seed 7})]
    (is (= :railgun (:ability-id entry)))
    (is (sessions/active? "owner-1"))
    (is (= 12 (:tick (second (first (sessions/tick! 12))))))
    (is (= "world" (get-in (sessions/context-for "owner-1" {})
                            [:context :world-id])))
    (sessions/remove! "owner-1")
    (is (not (sessions/active? "owner-1")))))
