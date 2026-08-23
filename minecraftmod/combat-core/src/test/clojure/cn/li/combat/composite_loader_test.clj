(ns cn.li.combat.composite-loader-test
  "Proves combat-core's thin cn.li.mcmod.runtime.safe-edn binding works
   end to end against a real classpath resource, not just node-core's
   generic loader tested against a fake in-memory document-loader
   (cn.li.node.composite-loader-test). Also proves the loaded composite
   really executes -- composites genuinely are just data read from a file
   now, not a hardcoded Clojure map, and the R4 lightning-strike-shaped
   demonstration composites this replaced still work the same way once
   converted to EDN under ac/src/main/resources/ac/combat/composites_v3/
   (see that manifest for the real, shipped content; this is combat-core's
   own self-contained fixture, kept independent of AC's resource tree so a
   neutral module's test suite never depends on the content-owning one)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.host-primitives :as host-primitives]
            [cn.li.combat.structural-primitives :as structural]
            [cn.li.combat.composite-loader :as composite-loader]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (node-flow/install!)
    (host-primitives/install!)
    (f)
    (node/reset-for-test!)))

(deftest install-loads-a-real-edn-resource-via-safe-edn-test
  (let [result (composite-loader/install! "cn/li/combat/composites_test/manifest.edn")]
    (is (= [:test/strike] (:registered result)))
    (is (empty? (:errors result)))
    (is (= :mid (:layer (node/descriptor :test/strike))))))

(deftest a-loaded-composite-actually-executes-test
  (composite-loader/install! "cn/li/combat/composites_test/manifest.edn")
  (let [actions (atom [])
        ctx {:locals {} :seed 0 :dispatch structural/dispatch
             :world-id "overworld" :ability-id :test :activation-seed 1
             :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
             :dispatch-query! (fn [_ _] nil)}
        result (node-flow/execute!
                {:component :test/strike :target "zombie-1" :position {:vec3 [0.0 64.0 0.0]} :amount 9.0}
                ctx)]
    (is (not (:finished? result)))
    (is (= #{:world/lightning :entity/damage} (set (map first @actions))))
    (is (= 9.0 (:amount (second (first (filter #(= :entity/damage (first %)) @actions))))))))
