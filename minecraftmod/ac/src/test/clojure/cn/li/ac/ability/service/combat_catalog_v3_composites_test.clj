(ns cn.li.ac.ability.service.combat-catalog-v3-composites-test
  "Proves the REAL v3 manifests -- ac/combat/composites_v3_manifest.edn and
   ac/vfx/composites_v3_manifest.edn, the shipped content the composite-
   loader mechanism (combat-core's and vfx-core's own composite_loader_test
   namespaces, tested against self-contained synthetic fixtures) exists to
   serve -- actually load with zero errors when combat-catalog/initialize!
   runs, and that at least one loaded composite genuinely executes. The
   deep per-node behavior of the loading mechanism itself is already
   covered where it belongs (combat-core/vfx-core's own test suites); this
   test is specifically about AC's real content compiling, per the mossy-
   wren plan's correction that composites must be authored as EDN files
   loaded through this mechanism, not hardcoded as Clojure maps."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.structural-primitives :as combat-structural]))

(deftest v3-manifests-load-with-no-errors-test
  (catalog/initialize!)
  (doseq [id [:combat/area-damage :combat/radial-impulse :combat/teleport-group
              :combat/area-break :combat/release-with-cost :fx/lightning-strike]]
    (is (= :mid (:layer (node/descriptor id))) (str id " should be a registered :mid composite")))
  (is (= :mid (:layer (node/descriptor :vfx.fx/charge-ring)))))

(deftest lightning-strike-composite-actually-executes-test
  (catalog/initialize!)
  (let [actions (atom [])
        vfx-signals (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :ability-id :test :activation-seed 1
             :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
             :dispatch-query! (fn [_ _] nil)
             :emit-action! (fn [action] (swap! actions conj [(:type action) action]))
             :emit-vfx! (fn [signal] (swap! vfx-signals conj signal))}
        result (node-flow/execute!
                {:component :fx/lightning-strike
                 :target "zombie-1" :position {:vec3 [0.0 64.0 0.0]}
                 :power 12.0 :budget {:resources {}}}
                ctx)]
    (is (not (:finished? result)))
    (is (some #{:world/lightning} (map first @actions)))
    (is (some #{:entity/damage} (map first @actions)))
    (is (= 1 (count @vfx-signals)))
    (is (= :lightning-impact (:effect-id (first @vfx-signals))))))
