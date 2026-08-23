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
            [cn.li.combat.structural-primitives :as combat-structural]
            [cn.li.vfx.vm :as vfx-vm])
  (:import [cn.li.mcmod.math V3]))

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

(deftest charge-ring-composite-actually-samples-real-geometry-test
  (catalog/initialize!)
  (let [batches (atom [])
        sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
        ;; A real ability's :effect/vfx position payload always arrives as
        ;; {:vec3 [x y z]} (cn.li.combat.vm/vec3-components' own literal
        ;; shape), never {:x :y :z} -- using that shape here, off-origin,
        ;; is what actually caught ops/->v3 and :vfx/ring-point silently
        ;; mishandling it (see those namespaces' own fix commits).
        call {:component :vfx.fx/charge-ring
              :center {:vec3 [10.0 5.0 -10.0]}
              :charge-ticks 10 :max-charge-ticks 20
              :points 8 :base-radius 2.0 :radius-growth 3.0
              :pulse-amplitude 0.0 :pulse-frequency 0.0
              :outer-color [1.0 1.0 1.0 1.0] :core-color [1.0 0.0 0.0 1.0]
              :punched? false}]
    (vfx-vm/sample-node! call {:input {} :seed 0 :sink sink})
    (is (= 8 (count @batches)))
    (let [op (first (:ops (first (:payload (first @batches)))))
          p1 ^V3 (:p1 op)
          dx (- (.-x p1) 10.0)
          dz (- (.-z p1) -10.0)]
      (is (= 5.0 (.-y p1)))
      (is (< (Math/abs (- 3.5 (Math/sqrt (+ (Math/pow dx 2) (Math/pow dz 2)))))
             1.0e-9))
      (is (= [1.0 1.0 1.0 1.0] (:color op))))))
