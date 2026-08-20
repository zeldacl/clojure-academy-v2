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
    (is (catalog/available? :arc-gen))
    (is (catalog/available? :thunder-clap))
    (is (catalog/available? :scatter-bomb))
    (is (catalog/available? :mark-teleport))
    (is (catalog/available? :penetrate-teleport))
    (is (not (catalog/available? :thunder-bolt)))
    (is (= :pending (catalog/migration-status :thunder-bolt)))
    (is (= :railgun (get-in state [:combat :abilities :railgun :id])))
    (let [parameters (get-in state [:combat :abilities :railgun :parameters])]
      (is (every? #(contains? % :value) (vals parameters)))
      (is (not-any? #(contains? % :source) (vals parameters)))
      (is (not-any? #(contains? % :path) (vals parameters))))
    (is (contains? (get-in state [:combat :composites]) :combat/impact-strike))
    (is (contains? (get-in state [:combat :composites]) :combat/area-damage))
    (is (contains? (get-in state [:combat :composites]) :combat/charged-area-damage))
    (is (contains? (get-in state [:combat :composites]) :target/raycast-destination))
    (is (contains? (get-in state [:combat :composites]) :target/hold-destination))
    (is (contains? (get-in state [:combat :composites]) :target/penetration-destination))
    (is (contains? (get-in state [:vfx :composites]) :vfx/beam-arc-fade))
    (is (contains? (get-in state [:vfx :composites]) :vfx/humanoid-marker))
    (is (= :railgun-beam (get-in state [:vfx :effects :railgun-beam :id])))
    (is (= :arc-gen (get-in state [:vfx :effects :arc-gen :id])))
    (is (= :thunder-clap (get-in state [:combat :abilities :thunder-clap :id])))
    (is (= :mark-teleport (get-in state [:combat :abilities :mark-teleport :id])))
    (is (= :penetrate-teleport (get-in state [:combat :abilities :penetrate-teleport :id])))
    (is (pos? (count (get-in state [:combat :abilities :penetrate-teleport :compiled-ir]))))
    (is (= :penetrate-teleport-marker
           (get-in state [:vfx :effects :penetrate-teleport-marker :id])))
    (is (= :penetrate-teleport-release
           (get-in state [:vfx :effects :penetrate-teleport-release :id])))
    (is (= :thunder-clap (get-in state [:vfx :effects :thunder-clap :id])))
    (let [marker (get-in state [:vfx :effects :mark-teleport-marker])
          model-node (some (fn [{:keys [node]}]
                             (when (= :vfx/model-marker (:component node)) node))
                           (get-in marker [:graph :children]))]
      (is (some? model-node))
      (is (= 7 (:frame-count model-node)))
      (is (= 2.5 (:frame-period-ticks model-node)))
      (is (= 7 (count (:parts model-node)))))))

(deftest migrated-entry-executes-compiled-program
  (catalog/initialize!)
  (let [compiled (get-in (catalog/catalog) [:combat :abilities :railgun])
        _ (do
            (is (pos? (count (:compiled-ir compiled))))
            (is (pos? (alength ^objects (.-objectConstants ^CompiledProgram (:compiled-program compiled))))))
        result (execution/execute! :railgun "owner-1"
                                   {:action :start
                                    :from {:caster/eye {:x 0.0 :y 0.0 :z 0.0}
                                           :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                                           :caster/id "owner-1"}
                                    :tunables {:charge-ticks 20}})]
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
