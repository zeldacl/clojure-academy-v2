(ns cn.li.combat.host-primitives-test
  "Coverage for host_primitives.clj: every confirmed true-primitive query/
   action component registers as a well-formed :layer :primitive
   descriptor, and its :impl genuinely shapes the request the way
   vm.clj's existing action-capability-by-component/query-capability-by-
   component dispatch does -- proven against a fake ctx, not the real
   ExecutionFrame/HostTable (that wiring is a later R2/R4 step)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.runtime :as runtime]
            [cn.li.combat.host-primitives :as host-primitives]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (host-primitives/install!)
    (f)
    (node/reset-for-test!)))

(def ^:private query-ids
  #{:target/raycast :target/raycast-fan :target/resolve-destination :target/block-placement
    :target/directional-destination-query :target/entities :target/entity-snapshot
    :owner/snapshot :target/item-held :target/saved-location :energy/target
    :target/blocks :terrain/propagate})

(def ^:private action-ids
  #{:inventory/consume :inventory/settle :inventory/place-or-drop :combat/damage
    :entity/trigger-behavior :entity/mark :energy/charge :combat/impulse :motion/flight
    :motion/velocity :owner/can-fly :combat/status :entity/teleport :entity/reset-fall-damage
    :entity/spawn :entity/discard :entity/configure :motion/entity-velocity
    :motion/entity-velocity-add :projectile/schedule-beam :block/break :block/set
    :world/sound :world/lightning :world/explosion :projectile/redirect
    :resource/enforce-floor :resource/add})

(def ^:private downgraded-ids
  "The 6 components the R2 audit confirmed must NOT appear here -- they
   become :mid composites in R4."
  #{:host/beam-trace :entity/radial-impulse :block/area-break :block/random-break
    :block/break-budget :entity/teleport-group})

(deftest every-expected-id-registers-as-primitive-test
  (doseq [id (into query-ids action-ids)]
    (let [d (node/descriptor id)]
      (is (some? d) (str id " must be registered"))
      (is (= :primitive (:layer d)))
      (is (fn? (:impl d))))))

(deftest downgraded-components-are-absent-test
  (doseq [id downgraded-ids]
    (is (nil? (node/descriptor id)) (str id " must not be registered as a primitive -- it is a confirmed R4 composite"))))

(deftest query-count-matches-audit-test
  (is (= 13 (count query-ids)))
  (is (= (count query-ids)
         (count (filter #(contains? (:effects (node/descriptor %)) :query) query-ids)))))

(deftest action-count-matches-audit-test
  (is (= 28 (count action-ids))))

(deftest registered-primitive-count-matches-exactly-test
  ;; Catches drift both ways: a component present in host_primitives.clj
  ;; but missing from this test's expected sets, or vice versa.
  (is (= (+ (count query-ids) (count action-ids)) (node/primitive-count))))

(defn- fake-ctx [dispatch-action! dispatch-query!]
  {:world-id "overworld" :owner "player-1" :activation-seed 42 :ability-id :railgun
   :dispatch-action! dispatch-action! :dispatch-query! dispatch-query!})

(deftest raycast-impl-shapes-query-request-and-returns-typed-output-test
  (let [seen (atom nil)
        ctx (fake-ctx nil (fn [capability request] (reset! seen [capability request]) {:vec3 [1.0 2.0 3.0]}))
        result (runtime/invoke-primitive!
                :target/raycast
                {:origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]} :distance 32.0}
                ctx)]
    (is (= :raycast (first @seen)))
    (is (= {:vec3 [0.0 64.0 0.0]} (:origin (second @seen))))
    (is (= "player-1" (:owner (second @seen))))
    (is (= {:hit {:vec3 [1.0 2.0 3.0]}} result))))

(deftest combat-damage-impl-dispatches-action-with-provenance-test
  (let [seen (atom nil)
        ctx (fake-ctx (fn [capability request] (reset! seen [capability request])) nil)
        result (runtime/invoke-primitive! :combat/damage {:target "zombie-1" :amount 5.0} ctx)]
    (is (= :entity/damage (first @seen)))
    (is (= "zombie-1" (:target (second @seen))))
    (is (= 5.0 (:amount (second @seen))))
    (is (= "overworld" (:world-id (second @seen))))
    (is (= 42 (:activation-seed (second @seen))))
    (is (= :railgun (:ability-id (second @seen))))
    (is (= {} result))))

(deftest world-lightning-impl-dispatches-through-action-pipeline-test
  (let [seen (atom nil)
        ctx (fake-ctx (fn [capability request] (reset! seen [capability request])) nil)]
    (runtime/invoke-primitive! :world/lightning {:position {:vec3 [1.0 64.0 1.0]}} ctx)
    (is (= :world/lightning (first @seen)))
    (is (= {:vec3 [1.0 64.0 1.0]} (:position (second @seen))))))

(deftest impl-only-receives-declared-inputs-test
  ;; invoke-primitive! filters unresolved/extraneous fields away -- proves
  ;; the mechanical drift guarantee for a real registered component, not
  ;; just the synthetic ones in runtime.clj's own unit tests.
  (let [seen (atom nil)
        ctx (fake-ctx (fn [capability request] (reset! seen request)) nil)]
    (runtime/invoke-primitive! :block/break {:position {:vec3 [0.0 0.0 0.0]} :bogus-extra-field 999} ctx)
    (is (not (contains? @seen :bogus-extra-field)))))
