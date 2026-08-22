(ns cn.li.ac.ability.service.combat-runtime-block-body-hit-test
  "Regression coverage for the single-execution-path collapse of the
   scripted block-body collision callback (P1b): a MagManip thrown block
   hitting an entity must route through Combat Core's own EDN dispatch
   instead of a platform-side apply-direct-damage! call, so the hit amount
   comes from the ability's own :throw-damage tunable, not a Java-embedded
   constant."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.combat.skill-runtime :as skill-runtime]))

(deftest mag-manip-block-body-hit-event-dispatches-combat-damage-test
  (catalog/initialize!)
  (is (catalog/available? :mag-manip))
  (let [result (skill-runtime/execute!
                (catalog/catalog) :mag-manip "owner-1"
                {:action :event :event :block-body-hit
                 :from {:caster/id "owner-1"
                        :caster/eye {:x 0.0 :y 1.62 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                        :caster/creative? false
                        :world/id "world"}
                 :context {:world-id "world" :target-id "target-1"}
                 :tunables {:throw-damage 10.0}})]
    (is (= :accepted (:status result)))
    (is (some #(and (= :entity/damage (:capability %))
                    (= "target-1" (:target %))
                    (= 10.0 (:amount %))
                    (= "world" (:world-id %)))
              (:actions result)))))
