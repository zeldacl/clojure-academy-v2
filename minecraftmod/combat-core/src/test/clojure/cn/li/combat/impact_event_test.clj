(ns cn.li.combat.impact-event-test
  "Generic (non-AC-specific) regression coverage for the single-execution-
   path pattern P1b collapsed the second combat-damage path into: a neutral
   platform callback reports only a fact (\"this entity/projectile hit
   something\"), and an ability's own :events entry -- not the platform
   caller -- decides whether/how much damage to apply, same as skill_runtime
   ns docstring says: 'phase selection, parameter/tunable materialization,
   VM execution ... action commit ordering' all stay owned by Combat Core.

   ac's own combat_runtime_block_body_hit_test.clj covers the real mag-manip
   content; this test proves the underlying :action :event -> :events
   dispatch mechanism itself at the combat-core layer, independent of any
   one ability's authored content."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.components :as components]
            [cn.li.combat.recipe :as recipe]
            [cn.li.combat.skill-runtime :as skill-runtime]))

(def ^:private impact-ability
  {:schema-version 1 :kind :ability :id :test/impact-ability :revision 1
   :activation :instant
   :program
   {:component :flow/phases
    :start {:component :flow/finish :outcome :no-trigger}
    :pulse {:component :flow/finish :outcome :continue}
    :release {:component :flow/finish :outcome :cancelled}
    :abort {:component :flow/finish :outcome :aborted}
    :events
    {:impact
     {:component :flow/sequence
      :steps [{:component :combat/damage
               :world-id {:from :world/id}
               :target {:ref [:context :target-id]}
               :amount 7.0
               :damage-type :skill}
              {:component :flow/finish :outcome :hit}]}}}})

(defn- catalog-with [ability]
  {:combat {:abilities {(:id ability) (recipe/compile-ability ability)}}})

(deftest an-external-hit-fact-dispatches-through-the-abilitys-own-events-entry-test
  (components/reset-for-test!)
  (let [result (skill-runtime/execute!
                (catalog-with impact-ability) :test/impact-ability "owner-1"
                {:action :event :event :impact
                 :from {:world/id "world"}
                 :context {:target-id "target-1"}})]
    (is (= :accepted (:status result)))
    (is (some #(and (= :entity/damage (:capability %))
                    (= "target-1" (:target %))
                    (= 7.0 (:amount %))
                    (= "world" (:world-id %)))
              (:actions result))
        "the ability's own :events entry, not the caller, decides the damage amount/target")))

(deftest an-unknown-event-name-fails-closed-instead-of-falling-through-to-any-action-test
  (components/reset-for-test!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"finished without reaching :flow/finish"
       (skill-runtime/execute!
        (catalog-with impact-ability) :test/impact-ability "owner-1"
        {:action :event :event :some-other-event
         :from {:world/id "world"}
         :context {:target-id "target-1"}}))
      "an event name the ability never declared in :events must fail closed, never silently fall through to any damage action"))
