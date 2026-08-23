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
              :combat/area-break :combat/release-with-cost :fx/lightning-strike
              :combat/break-budget :target/raycast-destination :combat/impact-strike
              :target/hold-destination :target/directional-destination
              :target/penetration-destination]]
    (is (= :mid (:layer (node/descriptor id))) (str id " should be a registered :mid composite")))
  (doseq [id [:vfx.fx/charge-ring :vfx.fx/block-progress :vfx.fx/trajectory-ribbon]]
    (is (= :mid (:layer (node/descriptor id))) (str id " should be a registered :mid composite"))))

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

(deftest block-progress-composite-simple-box-samples-real-geometry-test
  (catalog/initialize!)
  (let [batches (atom [])
        sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
        call {:component :vfx.fx/block-progress
              :target {:vec3 [0.0 64.0 0.0]}
              :progress 0.5 :color [255.0 0.0 0.0 200.0]
              :pulse-period 0.0 :width 1.0}]
    (vfx-vm/sample-node! call {:input {} :state {:age 0.0} :seed 0 :sink sink})
    ;; box mode: 12 edges, one batch per :vfx/timeline "always on" child
    (is (= 12 (count @batches)))
    (let [op (first (:ops (first (:payload (first @batches)))))
          p1 ^V3 (:p1 op) p2 ^V3 (:p2 op)]
      ;; shrink = 0.05 * (1 - 0.5) = 0.025; height defaults to width (1.0);
      ;; depth = width, so every axis shares the same [0.025, 0.975] span.
      (is (< (Math/abs (- 0.025 (.-x p1))) 1.0e-9))
      (is (< (Math/abs (- 64.025 (.-y p1))) 1.0e-9))
      (is (< (Math/abs (- 0.025 (.-z p1))) 1.0e-9))
      (is (< (Math/abs (- 0.975 (.-x p2))) 1.0e-9))
      ;; pulse-period 0.0 -> pulse is always 1.0, so alpha == color[3].
      (is (= [255.0 0.0 0.0 200.0] (:color op))))))

(deftest block-progress-composite-corner-decorated-samples-real-geometry-test
  (catalog/initialize!)
  (let [batches (atom [])
        sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
        call {:component :vfx.fx/block-progress
              :target {:vec3 [0.0 64.0 0.0]}
              :progress 1.0 :color [255.0 255.0 255.0 200.0]
              :pulse-period 0.0 :width 1.0 :corner-length 0.2}]
    (vfx-vm/sample-node! call {:input {} :state {:age 0.0} :seed 0 :sink sink})
    ;; corner mode: 8 corners x 3 stub segments each
    (is (= 24 (count @batches)))
    (let [op (first (:ops (first (:payload (first @batches)))))
          p1 ^V3 (:p1 op) p2 ^V3 (:p2 op)]
      ;; progress 1.0 -> shrink = 0; corner 0's first segment is the
      ;; vertical stub at (min-x, min-y, min-z) -> (min-x, min-y+0.2, min-z).
      (is (< (Math/abs (- 0.0 (.-x p1))) 1.0e-9))
      (is (< (Math/abs (- 64.0 (.-y p1))) 1.0e-9))
      (is (< (Math/abs (- 64.2 (.-y p2))) 1.0e-9))
      (is (< (Math/abs (- (.-x p1) (.-x p2))) 1.0e-9)))))

(deftest block-progress-composite-resolves-target-through-a-caller-scope-reference-test
  ;; Regression: a first version of block_progress.edn extracted :target's
  ;; x/y/z via {:ref [:input :target :vec3 0]} -- a nested-path :ref, which
  ;; cn.li.node.composite/substitute-inputs resolves at composite EXPAND
  ;; time (compile time) against whatever value the CALL SITE supplied.
  ;; Every earlier test here called the composite directly with a literal
  ;; {:vec3 [...]}, which masked the bug -- but target_box_session.edn (the
  ;; real content) calls it as :target {:ref [:input :position]}, an
  ;; UNRESOLVED reference into the caller's own scope, only resolvable once
  ;; sampling actually runs. get-in-ing into that reference map at expand
  ;; time silently returned nil, so :tx/:ty/:tz all came out 0.0 regardless
  ;; of the real target position. This test reproduces the real call shape
  ;; -- a :vfx/let binding a real position under :position, with the
  ;; composite call referencing {:ref [:input :position]} for :target,
  ;; exactly like target_box_session.edn's own :graph -- to prove the
  ;; {:expr :vec3/x|y|z ...} fix actually resolves at sample time instead.
  (catalog/initialize!)
  (let [batches (atom [])
        sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
        call {:component :vfx/let
              :bindings {:position {:vec3 [3.0 70.0 -5.0]}}
              :child {:component :vfx.fx/block-progress
                      :target {:ref [:input :position]}
                      :progress 0.0 :color [1.0 1.0 1.0 1.0] :width 1.0}}]
    (vfx-vm/sample-node! call {:input {} :state {:age 0.0} :seed 0 :sink sink})
    (is (= 12 (count @batches)))
    (let [op (first (:ops (first (:payload (first @batches)))))
          p1 ^V3 (:p1 op)]
      ;; shrink = 0.05 * (1 - 0.0) = 0.05; min corner = target + shrink.
      (is (< (Math/abs (- 3.05 (.-x p1))) 1.0e-9))
      (is (< (Math/abs (- 70.05 (.-y p1))) 1.0e-9))
      (is (< (Math/abs (- -4.95 (.-z p1))) 1.0e-9)))))

(deftest trajectory-ribbon-composite-samples-real-geometry-test
  ;; Expected coordinates computed from the SAME closed form vfx-core's own
  ;; cn.li.vfx.trajectory-ribbon-math-test already proved numerically
  ;; matches the original per-tick loop -- drag=0.9, gravity=9.8, dt=0.02,
  ;; vx0=2.0, vy0=5.0, vz0=0.0, origin=(0,64,0), 5 segments -> 4 lines.
  (catalog/initialize!)
  (let [batches (atom [])
        sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
        call {:component :vfx.fx/trajectory-ribbon
              :origin {:vec3 [0.0 64.0 0.0]}
              :initial-velocity {:vec3 [2.0 5.0 0.0]}
              :look-dir {:vec3 [0.0 0.0 1.0]}
              :drag 0.9 :gravity 9.8 :dt 0.02 :segments 5
              :can-perform? true}]
    (vfx-vm/sample-node! call {:input {} :seed 0 :sink sink})
    (is (= 4 (count @batches)) "5 segments -> 4 connecting lines")
    (let [first-op (first (:ops (first (:payload (first @batches)))))
          last-op (first (:ops (first (:payload (last @batches)))))
          p1 ^V3 (:p1 first-op) p2-first ^V3 (:p2 first-op)
          from-last ^V3 (:p1 last-op) to-last ^V3 (:p2 last-op)]
      ;; point[0] == origin (all offsets zero)
      (is (< (Math/abs (- 0.0 (.-x p1))) 1.0e-9))
      (is (< (Math/abs (- 64.0 (.-y p1))) 1.0e-9))
      ;; point[1]
      (is (< (Math/abs (- 0.036 (.-x p2-first))) 1.0e-9))
      (is (< (Math/abs (- 64.09 (.-y p2-first))) 1.0e-9))
      ;; the 4th (last) line connects point[3] -> point[4]
      (is (< (Math/abs (- 0.09756 (.-x from-last))) 1.0e-6))
      (is (< (Math/abs (- 64.23366879999999 (.-y from-last))) 1.0e-6))
      (is (< (Math/abs (- 0.12380400000000004 (.-x to-last))) 1.0e-6))
      (is (< (Math/abs (- 64.28971792 (.-y to-last))) 1.0e-6))
      ;; no ready-color/blocked-color/style-color supplied -> falls all
      ;; the way through the (or ...) chain to the hardcoded default.
      (is (= [255.0 255.0 255.0 255.0] (:color first-op))))))

(deftest trajectory-ribbon-composite-resolves-origin-through-a-caller-scope-reference-test
  ;; Same regression shape as block-progress's caller-scope test: real
  ;; content (trajectory_ribbon_session.edn) calls this composite with
  ;; :origin {:ref [:input :origin]}, an unresolved reference into the
  ;; effect's own spawn payload, never a literal.
  (catalog/initialize!)
  (let [batches (atom [])
        sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
        call {:component :vfx/let
              :bindings {:spawn-origin {:vec3 [8.0 65.0 -3.0]}}
              :child {:component :vfx.fx/trajectory-ribbon
                      :origin {:ref [:input :spawn-origin]}
                      :initial-velocity {:vec3 [0.0 0.0 0.0]}
                      :look-dir {:vec3 [0.0 0.0 1.0]}
                      :drag 1.0 :segments 2}}]
    (vfx-vm/sample-node! call {:input {} :seed 0 :sink sink})
    (is (= 1 (count @batches)))
    (let [op (first (:ops (first (:payload (first @batches)))))
          p1 ^V3 (:p1 op)]
      (is (< (Math/abs (- 8.0 (.-x p1))) 1.0e-9))
      (is (< (Math/abs (- 65.0 (.-y p1))) 1.0e-9))
      (is (< (Math/abs (- -3.0 (.-z p1))) 1.0e-9)))))

(deftest break-budget-composite-spends-energy-in-order-and-skips-unaffordable-blocks-test
  ;; Regression-style proof that :flow/foreach's accumulator threading
  ;; (each iteration's :data/bind carries into the next iteration's
  ;; locals -- see node-core/flow.clj's own docstring) is enough to port
  ;; cn.li.combat.actions/commit-block-break-budget!'s energy-accounting
  ;; loop with no new engine primitive. energy=5.0, blocks cost
  ;; [3.0 10.0 2.0]: block 0 fits (remaining -> 2.0), block 1 (cost 10.0)
  ;; does NOT fit the remaining 2.0 and is skipped WITHOUT stopping the
  ;; loop, block 2 (cost 2.0) exactly fits the remaining 2.0.
  (catalog/initialize!)
  (let [actions (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :ability-id :test :activation-seed 1
             :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
             :dispatch-query! (fn [_ _] nil)}
        result (node-flow/execute!
                {:component :combat/break-budget
                 :blocks [{:position {:vec3 [0.0 64.0 0.0]} :hardness 3.0}
                          {:position {:vec3 [1.0 64.0 0.0]} :hardness 10.0}
                          {:position {:vec3 [2.0 64.0 0.0]} :hardness 2.0}]
                 :energy 5.0}
                ctx)
        broken-positions (mapv #(:position (second %)) @actions)]
    (is (not (:finished? result)))
    (is (= [{:vec3 [0.0 64.0 0.0]} {:vec3 [2.0 64.0 0.0]}] broken-positions)
        "block index 1 (cost 10.0) should be skipped, not stop the loop")
    (is (every? #(= :block/break (first %)) @actions))
    (is (every? #(false? (:drop? (second %))) @actions)
        "default :drop-chance 0.0 never drops")))

(deftest raycast-destination-composite-exposes-both-outputs-and-tags-the-right-query-kind-test
  ;; Proves the composite :outputs mechanism itself (:from [:local ...] ->
  ;; a :data/bind step appended after the body) against REAL shipped
  ;; content, not just node-core's own synthetic fixtures -- and proves
  ;; :target/resolve-destination's :query-kind fix (43117928c) actually
  ;; reaches the second host call this composite makes.
  (catalog/initialize!)
  (let [queries (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :owner "player-1"
             :dispatch-query! (fn [capability request]
                                (swap! queries conj [capability request])
                                (if (= capability :raycast)
                                  (if (contains? request :hit)
                                    {:x 5.0 :y 65.0 :z 0.0}
                                    {:hit-type :block :x 4.0 :y 64.0 :z 0.0})
                                  nil))}
        result (node-flow/execute!
                {:component :target/raycast-destination
                 :origin {:vec3 [0.0 64.0 0.0]}
                 :direction {:vec3 [0.0 0.0 1.0]}
                 :distance 8.0
                 :bind {:hit :h :destination :d}}
                ctx)]
    (is (= 2 (count @queries)))
    (is (nil? (:query-kind (second (first @queries))))
        "the first call (:target/raycast) must NOT tag a :query-kind")
    (is (= :resolve-destination (:query-kind (second (second @queries)))))
    (is (= {:hit-type :block :x 4.0 :y 64.0 :z 0.0} (get-in result [:locals :h])))
    (is (= {:x 5.0 :y 65.0 :z 0.0} (get-in result [:locals :d])))))

(deftest impact-strike-composite-applies-damage-then-runs-the-caller-supplied-callback-test
  (catalog/initialize!)
  (let [actions (atom [])
        vfx-signals (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :ability-id :test :activation-seed 1
             :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
             :dispatch-query! (fn [_ _] nil)
             :emit-vfx! (fn [signal] (swap! vfx-signals conj signal))}
        result (node-flow/execute!
                {:component :combat/impact-strike
                 :target "zombie-1" :amount 7.0 :damage-type :fire
                 :on-impact {:component :effect/vfx :effect-id :scorch-mark :operation :spawn
                             :payload {}}}
                ctx)]
    (is (not (:finished? result)))
    (is (= [[:entity/damage {:target "zombie-1" :amount 7.0 :damage-type :fire}]]
           (mapv (fn [[cap req]] [cap (select-keys req [:target :amount :damage-type])]) @actions)))
    (is (= 1 (count @vfx-signals)))
    (is (= :scorch-mark (:effect-id (first @vfx-signals))))))

(deftest hold-destination-composite-computes-distance-and-delegates-through-nested-composites-test
  ;; hold-distance = min(min(0.5*(3+1), 10.0), 100.0/1.0) = min(2.0, 100.0) = 2.0.
  ;; :target/hold-destination -> :target/raycast-destination -> :target/raycast
  ;; + :target/resolve-destination is 3 levels of composite/primitive
  ;; nesting; proves locals stay correctly scoped and outputs propagate
  ;; all the way back out.
  (catalog/initialize!)
  (let [queries (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :owner "player-1"
             :dispatch-query! (fn [capability request]
                                (swap! queries conj [capability request])
                                (if (contains? request :hit)
                                  {:x 1.0 :y 65.0 :z 0.0}
                                  {:hit-type :block :x 1.0 :y 64.0 :z 0.0}))}
        result (node-flow/execute!
                {:component :target/hold-destination
                 :origin {:vec3 [0.0 64.0 0.0]}
                 :direction {:vec3 [0.0 0.0 1.0]}
                 :hold-ticks 3.0 :range-per-hold-tick 0.5 :maximum-range 10.0
                 :available-resource 100.0 :resource-per-distance 1.0
                 :bind {:hit :h :destination :d}}
                ctx)]
    (is (= 2.0 (:distance (second (first @queries)))))
    (is (= {:x 1.0 :y 65.0 :z 0.0} (get-in result [:locals :d])))
    (is (= {:hit-type :block :x 1.0 :y 64.0 :z 0.0} (get-in result [:locals :h])))))

(deftest directional-destination-composite-tags-the-right-query-kind-test
  (catalog/initialize!)
  (let [queries (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :owner "player-1"
             :dispatch-query! (fn [capability request] (swap! queries conj [capability request]) {:x 0.0 :y 64.0 :z 1.0})}
        result (node-flow/execute!
                {:component :target/directional-destination
                 :origin {:vec3 [0.0 64.0 0.0]} :look {:vec3 [0.0 0.0 1.0]}
                 :eye-y 65.6 :direction :left :distance 3.0
                 :bind {:destination :d}}
                ctx)]
    (is (= 1 (count @queries)))
    (is (= :directional-destination (:query-kind (second (first @queries)))))
    (is (= :left (:direction (second (first @queries)))))
    (is (= {:x 0.0 :y 64.0 :z 1.0} (get-in result [:locals :d])))))

(deftest penetration-destination-composite-derives-query-kind-from-policy-and-computes-effective-distance-test
  ;; effective-distance = min(20.0, 100.0/4.0) = min(20.0, 25.0) = 20.0.
  (catalog/initialize!)
  (let [queries (atom [])
        ctx {:locals {} :seed 0 :dispatch combat-structural/dispatch
             :world-id "overworld" :owner "player-1"
             :dispatch-query! (fn [capability request] (swap! queries conj [capability request])
                                {:position {:x 0.0 :y 64.0 :z 20.0} :marker-position {:x 0.0 :y 64.0 :z 20.0}})}
        result (node-flow/execute!
                {:component :target/penetration-destination
                 :origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]}
                 :distance 20.0 :scan-step 0.5 :clearance-steps 3
                 :available-resource 100.0 :resource-per-distance 4.0
                 :bind {:destination :d}}
                ctx)]
    (is (= 1 (count @queries)))
    (is (= :penetration (:query-kind (second (first @queries)))))
    (is (= 20.0 (:distance (second (first @queries)))))
    (is (= :penetration (get-in (second (first @queries)) [:policy :type])))
    (is (= 0.5 (get-in (second (first @queries)) [:policy :scan-step])))
    (is (some? (get-in result [:locals :d])))))
