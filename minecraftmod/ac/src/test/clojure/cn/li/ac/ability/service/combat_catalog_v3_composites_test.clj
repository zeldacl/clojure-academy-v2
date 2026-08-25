(ns cn.li.ac.ability.service.combat-catalog-v3-composites-test
  "Proves the REAL v3 manifests -- ac/combat/composites_v3_manifest.edn and
   ac/vfx/composites_v3_manifest.edn, the shipped content the composite-
   loader mechanism (combat-core's and vfx-core's own compiler tests
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
