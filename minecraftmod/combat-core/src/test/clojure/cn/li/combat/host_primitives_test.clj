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
    :target/blocks :terrain/propagate :target/beam-trace})

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
  (is (= 14 (count query-ids)))
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

(deftest resolve-destination-impl-tags-the-right-query-kind-test
  ;; Regression: cn.li.combat.platform/raycast! is a single :raycast
  ;; capability multiplexed by :query-kind into 6 different handlers.
  ;; :target/resolve-destination and :target/block-placement were
  ;; registered without one, so both silently called the DEFAULT handler
  ;; (basic-raycast, which ignores :hit/:policy entirely) instead of
  ;; resolve-destination -- never caught because nothing had exercised
  ;; these primitives against a query-kind-aware fake host until now, only
  ;; against a fake that ignores query-kind and just echoes the request
  ;; shape back (see raycast-impl-shapes-query-request-and-returns-typed-
  ;; output-test above, which never asserted on :query-kind at all).
  (let [seen (atom nil)
        ctx (fake-ctx nil (fn [capability request] (reset! seen [capability request]) nil))]
    (runtime/invoke-primitive!
     :target/resolve-destination
     {:hit {:hit-type :block} :origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]} :distance 5.0}
     ctx)
    (is (= :resolve-destination (:query-kind (second @seen)))))
  (let [seen (atom nil)
        ctx (fake-ctx nil (fn [capability request] (reset! seen [capability request]) nil))]
    (runtime/invoke-primitive!
     :target/block-placement
     {:hit {:hit-type :block} :origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]} :distance 5.0}
     ctx)
    (is (= :block-placement (:query-kind (second @seen)))))
  (let [seen (atom nil)
        ctx (fake-ctx nil (fn [capability request] (reset! seen [capability request]) {:vec3 [1.0 2.0 3.0]}))]
    (runtime/invoke-primitive!
     :target/raycast {:origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]} :distance 32.0}
     ctx)
    (is (not (contains? (second @seen) :query-kind))
        "plain :target/raycast must fall through to the default handler, not tag a :query-kind")))

(deftest raycast-impl-derives-penetration-query-kind-from-policy-test
  ;; Regression: the OLD v2 execution path (vm.clj's
  ;; invoke-query-component!) derives :query-kind :penetration from the
  ;; request DATA (:policy :type :penetration), not from the component id
  ;; -- unlike the other 5 :query-kind modes. target_penetration_destination.edn
  ;; (real v2 content) calls plain :target/raycast this way, never a
  ;; dedicated primitive.
  (let [seen (atom nil)
        ctx (fake-ctx nil (fn [capability request] (reset! seen [capability request]) nil))]
    (runtime/invoke-primitive!
     :target/raycast
     {:origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]} :distance 32.0
      :policy {:type :penetration :scan-step 0.5 :clearance-steps 3}}
     ctx)
    (is (= :penetration (:query-kind (second @seen))))))

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

(deftest beam-trace-impl-computes-falloff-and-excludes-out-of-radius-entities-test
  ;; Same geometry cn.li.combat.beam/trace-core has always computed (shared
  ;; with v2's :host/beam-trace via trace!) -- entity A sits dead-center on
  ;; the beam axis 5 blocks out (radial 0 -> full falloff); entity B is 5
  ;; blocks off-axis, well past radius*1.2, and must be excluded entirely.
  (let [queries (atom [])
        dispatch-query!
        (fn [capability request]
          (swap! queries conj [capability request])
          (case capability
            :entity/select
            [{:id "entity-a" :type "zombie" :position {:x 0.0 :y 64.0 :z 5.0} :eye-height 0.0}
             {:id "entity-b" :type "zombie" :position {:x 5.0 :y 64.0 :z 5.0} :eye-height 0.0}]
            :block/select [{:position {:x 0.0 :y 63.0 :z 1.0} :hardness 1.0 :block-id :stone}]
            nil))
        ctx (fake-ctx nil dispatch-query!)
        result (runtime/invoke-primitive!
                :target/beam-trace
                {:origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]}
                 :length 10.0 :radius 1.0 :damage 20.0 :damage-type :skill}
                ctx)
        beam (:beam result)]
    (is (= 1 (count (:entities beam))))
    (is (= "entity-a" (:id (first (:entities beam)))))
    (is (= 20.0 (:damage (first (:entities beam))))
        "radial 0 on-axis -> falloff 1.0 -> full damage")
    (is (= :skill (:damage-type (first (:entities beam)))))
    (is (pos? (count (:blocks beam))))
    (is (some #(= :block/select (first %)) @queries))
    (is (some #(= :entity/select (first %)) @queries))))

(deftest impl-only-receives-declared-inputs-test
  ;; invoke-primitive! filters unresolved/extraneous fields away -- proves
  ;; the mechanical drift guarantee for a real registered component, not
  ;; just the synthetic ones in runtime.clj's own unit tests.
  (let [seen (atom nil)
        ctx (fake-ctx (fn [capability request] (reset! seen request)) nil)]
    (runtime/invoke-primitive! :block/break {:position {:vec3 [0.0 0.0 0.0]} :bogus-extra-field 999} ctx)
    (is (not (contains? @seen :bogus-extra-field)))))
