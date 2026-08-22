(ns cn.li.vfx.vm-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.vm :as vm])
  (:import [cn.li.mcmod.math V3]))

(deftest resolve-value-handles-input-state-and-nesting
  (let [ctx {:input {:start {:x 1.0 :y 2.0 :z 3.0}} :state {:age 5.0}}]
    (is (= {:x 1.0 :y 2.0 :z 3.0} (vm/resolve-value {:ref [:input :start]} ctx)))
    (is (= 1.0 (vm/resolve-value {:ref [:input :start :x]} ctx)))
    (is (= 5.0 (vm/resolve-value {:ref [:state :age]} ctx)))
    (is (= [{:x 1.0}] (vm/resolve-value [{:x {:ref [:input :start :x]}}] ctx)))
    (is (= "literal" (vm/resolve-value "literal" ctx)))
    (is (= 3 (vm/resolve-value 3 ctx)))))

(deftest resolve-value-rejects-unknown-scope
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown VFX :ref scope"
                        (vm/resolve-value {:ref [:bogus :x]} {}))))

(deftest resolve-value-evaluates-expr-forms-test
  ;; Regression for the energy_orb_session.edn bug: {:expr ...} used to fall
  ;; through to the generic map? branch and resolve to the literal opcode
  ;; map itself instead of being evaluated.
  (let [ctx {:input {:radius 4.0} :seed 7}]
    (is (= 2.6 (vm/resolve-value {:expr :math/mul :args [{:ref [:input :radius]} 0.65]} ctx)))))

(deftest resolve-value-evaluates-nested-expr-inside-a-map-test
  (let [ctx {:input {:radius 4.0} :seed 7}]
    (is (= {:from 2.6 :to 4.0}
           (vm/resolve-value {:from {:expr :math/mul :args [{:ref [:input :radius]} 0.65]}
                              :to {:ref [:input :radius]}}
                             ctx)))))

(defn- collecting-sink []
  (let [batches (atom [])]
    {:sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
     :batches batches}))

(deftest timeline-only-renders-children-whose-at-has-elapsed
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/timeline
               :duration-ticks 100
               :children [{:at 0 :node {:component :vfx/ring :center {:vec3 [0 0 0]}
                                         :radius 1.0 :segments 8 :color [255 255 255 255]}}
                          {:at 10 :node {:component :vfx/ring :center {:vec3 [0 0 0]}
                                         :radius 2.0 :segments 8 :color [255 255 255 255]}}]}]
    (vm/sample! graph {:age 5.0 :input {}} {:sink sink})
    (is (= 1 (count @batches)) "only the :at 0 child has elapsed at age 5")
    (vm/sample! graph {:age 10.0 :input {}} {:sink sink})
    (is (= 3 (count @batches)) "both children have elapsed at age 10 (1 + 2 more)")))

(deftest fade-scales-child-batch-alpha-by-elapsed-fraction
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/fade
               :from-tick 0 :to-tick 10 :from-alpha 1.0 :to-alpha 0.0
               :child {:component :vfx/ring :center {:vec3 [0 0 0]}
                       :radius 1.0 :segments 8 :color [255 255 255 200]}}]
    (vm/sample! graph {:age 5.0 :input {}} {:sink sink})
    (let [[_ _ _ a] (:color (first (:payload (first @batches))))]
      (is (< 90.0 a 110.0) "alpha halfway through a 200-alpha fade-out is ~100"))))

(deftest ring-node-emits-one-line-batch
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/ring :center {:ref [:input :center]}
               :radius {:from 0.5 :to 1.5} :segments 12 :color [1 2 3 255]}]
    (vm/sample! graph {:age 0.0 :input {:center {:vec3 [1.0 2.0 3.0]}}} {:sink sink})
    (let [batch (first @batches)]
      (is (= :world-after-translucent (:stage batch)))
      (is (= :line (:primitive batch)))
      (is (= 1 (:count batch)))
      (is (= {:vec3 [1.0 2.0 3.0]} (:center (first (:payload batch)))))
      (is (= 0.5 (:radius-from (first (:payload batch))))))))

;; --- :vfx/line, :vfx/quad: end-to-end proof the render-op contract fix
;; actually works (R3, mossy-wren plan). These are the only two components
;; whose emitted batch shape ({:primitive :mesh :payload [{:ops [...]}]})
;; matches what platform-src's :draw-batch! handler (via
;; render-presentation-geometry!/sort-ops) has ever understood -- every
;; other leaf node's :variant-tagged parameter map silently draws nothing.
;; This is the closest thing to a real render check this environment can
;; run: it cannot verify pixels on screen, but it proves the DATA a vfx
;; graph produces is byte-for-byte the shape the renderer's own op
;; vocabulary (:kind :line/:quad, real V3 corners, vector color) requires,
;; not a shape that happens to also be named "mesh".

(deftest line-node-emits-mesh-ops-batch-with-real-v3-points-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/line :from {:x 1.0 :y 2.0 :z 3.0} :to {:x 4.0 :y 5.0 :z 6.0}
               :color [255 0 0 255]}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (let [batch (first @batches)
          plan (first (:payload batch))
          op (first (:ops plan))]
      (is (= :mesh (:primitive batch)))
      (is (= :ops (:variant batch)))
      (is (= 1 (count (:payload batch))) "one plan per emit! call")
      (is (= :line (:kind op)))
      (is (instance? V3 (:p1 op)))
      (is (instance? V3 (:p2 op)))
      (is (= 1.0 (.-x ^V3 (:p1 op))))
      (is (= 6.0 (.-z ^V3 (:p2 op))))
      (is (= [255 0 0 255] (:color op))))))

(deftest line-node-accepts-positional-vector-points-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/line :from [0.0 1.0 2.0] :to [3.0 4.0 5.0] :color [1 2 3 4]}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= 2.0 (.-z ^V3 (:p1 op))))
      (is (= 3.0 (.-x ^V3 (:p2 op)))))))

(deftest quad-node-emits-mesh-ops-batch-with-four-corners-and-uv-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/quad
               :p0 {:x 0.0 :y 0.0 :z 0.0} :p1 {:x 1.0 :y 0.0 :z 0.0}
               :p2 {:x 1.0 :y 1.0 :z 0.0} :p3 {:x 0.0 :y 1.0 :z 0.0}
               :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
               :color [10 20 30 255] :texture "academy:textures/effects/beam.png"}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= :quad (:kind op)))
      (is (every? #(instance? V3 (get op %)) [:p0 :p1 :p2 :p3]))
      (is (= 1.0 (:u1 op)))
      (is (= "academy:textures/effects/beam.png" (:texture op))))))

;; --- generic structural nodes: :vfx/let, :vfx/curve, :vfx/branch, :vfx/repeat

(deftest let-node-binds-into-input-for-child-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/let :bindings {:mid {:ref [:input :radius]}}
               :child {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0}
                       :to {:ref [:input :mid]} :color [1 1 1 1]}}]
    (vm/sample! graph {:age 0.0 :input {:radius {:x 5.0 :y 6.0 :z 7.0}}} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= 7.0 (.-z ^V3 (:p2 op)))))))

(deftest let-does-not-leak-into-sibling-scope-test
  ;; The binding only exists inside :child's own ctx -- a sibling under
  ;; :vfx/timeline sampled separately never sees it; its own {:ref [:input
  ;; :mid]} resolves against the ORIGINAL (unmodified) :input, i.e. nil.
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/timeline :duration-ticks 10
               :children [{:at 0 :node {:component :vfx/let :bindings {:mid {:x 1.0 :y 1.0 :z 1.0}}
                                        :child {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0}
                                                :to {:ref [:input :mid]} :color [1 1 1 1]}}}
                          {:at 0 :node {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0}
                                       :to {:x 9.0 :y 9.0 :z 9.0} :color [1 1 1 1]}}]}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (is (= 2 (count @batches)))
    (is (= 1.0 (.-x ^V3 (:p2 (first (:ops (first (:payload (first @batches)))))))))
    (is (= 9.0 (.-x ^V3 (:p2 (first (:ops (first (:payload (second @batches)))))))))))

(deftest curve-node-samples-keyframes-into-input-test
  ;; sample!'s ctx :state is the ENTIRE per-instance state map it was
  ;; called with (see sample!'s own :state state binding, not (:state
  ;; state)) -- :age-ratio must live at that map's top level, not nested
  ;; under a second :state key, to be visible as {:ref [:state :age-ratio]}.
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/curve :curve [[0.0 0.0] [1.0 10.0]] :progress {:ref [:state :age-ratio]} :as :radius
               :child {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0}
                       :to {:x {:ref [:input :radius]} :y 0.0 :z 0.0} :color [1 1 1 1]}}]
    (vm/sample! graph {:age 0.0 :input {} :age-ratio 0.5} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= 5.0 (.-x ^V3 (:p2 op)))))))

(deftest branch-node-picks-then-when-true-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/branch :when {:ref [:input :active?]}
               :then {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0} :to {:x 1.0 :y 0.0 :z 0.0} :color [1 1 1 1]}
               :else {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0} :to {:x 2.0 :y 0.0 :z 0.0} :color [1 1 1 1]}}]
    (vm/sample! graph {:age 0.0 :input {:active? true}} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= 1.0 (.-x ^V3 (:p2 op)))))))

(deftest branch-node-picks-else-when-false-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/branch :when {:ref [:input :active?]}
               :then {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0} :to {:x 1.0 :y 0.0 :z 0.0} :color [1 1 1 1]}
               :else {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0} :to {:x 2.0 :y 0.0 :z 0.0} :color [1 1 1 1]}}]
    (vm/sample! graph {:age 0.0 :input {:active? false}} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= 2.0 (.-x ^V3 (:p2 op)))))))

(deftest repeat-node-runs-body-count-times-with-index-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/repeat :count 3 :index-as :i
               :body {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0}
                      :to {:x {:ref [:input :i]} :y 0.0 :z 0.0} :color [1 1 1 1]}}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (is (= 3 (count @batches)))
    (is (= [0.0 1.0 2.0] (mapv (fn [b] (.-x ^V3 (:p2 (first (:ops (first (:payload b))))))) @batches)))))

(deftest repeat-node-does-not-leak-index-to-caller-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/timeline :duration-ticks 10
               :children [{:at 0 :node {:component :vfx/repeat :count 2 :index-as :i
                                        :body {:component :vfx/line :from {:x 0.0 :y 0.0 :z 0.0}
                                               :to {:x {:ref [:input :i]} :y 0.0 :z 0.0} :color [1 1 1 1]}}}]}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (is (= 2 (count @batches)) "the repeat body ran twice, each with its own ctx")))

(deftest quad-node-defaults-missing-uv-to-full-range-test
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/quad
               :p0 {:x 0.0 :y 0.0 :z 0.0} :p1 {:x 1.0 :y 0.0 :z 0.0}
               :p2 {:x 1.0 :y 1.0 :z 0.0} :p3 {:x 0.0 :y 1.0 :z 0.0}
               :color [1 1 1 1]}]
    (vm/sample! graph {:age 0.0 :input {}} {:sink sink})
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (= 0.0 (:u0 op)))
      (is (= 1.0 (:u1 op)))
      (is (= 0.0 (:v0 op)))
      (is (= 1.0 (:v1 op))))))

(deftest charge-ring-node-emits-parameterized-ring-payload
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/charge-ring
               :center {:ref [:input :center]}
               :charge-ticks 25 :max-charge-ticks 50 :points 16
               :base-radius 0.1 :radius-growth 0.16
               :pulse-amplitude 0.0 :pulse-frequency 0.22
               :outer-color [236 170 93 170]
               :core-color [241 240 222 220]
               :punched? false}]
    (vm/sample! graph {:age 0.0 :input {:center {:vec3 [1.0 2.0 3.0]}}}
                {:sink sink})
    (let [payload (first (:payload (first @batches)))]
      (is (= :charge-ring (:variant payload)))
      (is (= 16 (:points payload)))
      (is (= 0.5 (:progress payload)))
      (is (= 0.18 (:radius payload))))))

(deftest directional-wave-node-emits-seeded-ring-payload
  (let [{:keys [sink batches]} (collecting-sink)
        graph {:component :vfx/directional-wave
               :position {:ref [:input :position]}
               :direction {:ref [:input :direction]}
               :ring-count-min 2 :ring-count-max 3
               :life-ticks 15 :ring-life-min 8 :ring-life-max 12
               :ring-life-jitter 0.0 :ring-offset-step 1.5
               :ring-offset-jitter 0.3 :ring-size-min 0.8 :ring-size-max 1.2
               :time-offset-step 2.0 :time-offset-jitter 1
               :fade-in-ratio 0.2 :full-ratio 0.8 :fade-out-ratio 0.2
               :growth-ticks 20.0 :initial-scale 0.4 :mid-scale 0.8
               :mid-ratio 0.2 :final-scale 1.5 :forward-speed 0.025
               :texture "generic" :color [188 252 238 220] :seed 7}]
    (vm/sample! graph {:age 5.0 :input {:position {:vec3 [0.0 0.0 0.0]}
                                         :direction {:vec3 [0.0 0.0 2.0]}}}
                {:sink sink})
    (let [payload (first (:payload (first @batches)))
          rings (:rings payload)]
      (is (= :directional-wave (:variant payload)))
      (is (<= 2 (count rings) 3))
      (is (every? #(and (map? (:center %))
                        (<= 0.0 (double (:alpha %)) 1.0)) rings)))))

(deftest unknown-component-throws-instead-of-silently-dropping
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown VFX component"
                        (vm/sample! {:component :vfx/does-not-exist} {:age 0.0 :input {}} {:sink nil}))))

(deftest eval-bounds-computes-midpoint-and-radius
  (let [bounds-node {:component :vfx/beam-bounds
                     :start {:ref [:input :start]} :end {:ref [:input :end]}
                     :radius 1.0}
        ctx {:input {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 10.0 :y 0.0 :z 0.0}}}
        result (vm/eval-bounds bounds-node ctx)]
    (is (= {:x 5.0 :y 0.0 :z 0.0} (:center result)))
    (is (= 6.0 (:radius result)) "1.0 base radius + 5.0 half-length")))

(deftest eval-bounds-returns-nil-for-no-bounds-doc
  (is (nil? (vm/eval-bounds nil {}))))

(deftest init-state-seeds-age-zero-and-carries-spawn-params
  (let [state (vm/init-state {:seed 42 :params {:sound-id "boom"}})]
    (is (= 0.0 (:age state)))
    (is (= 42 (:seed state)))
    (is (= {:sound-id "boom"} (:input state)))))

(deftest advance-state-merges-event-payloads-and-advances-age
  (let [graph {:component :vfx/timeline :duration-ticks 100 :children []}
        state {:age 0.0 :input {:a 1}}
        events [{:event :update :payload {:b 2}}]
        next (vm/advance-state graph state {:events events :delta-seconds 0.05})]
    (is (= 1.0 (:age next)) "0.05s * 20 ticks/s = 1 tick")
    (is (= {:a 1 :b 2} (:input next)))))

(deftest advance-state-ends-instance-once-timeline-duration-elapses
  (let [graph {:component :vfx/timeline :duration-ticks 2 :children []}
        one-tick 0.05] ; 1/20 second == 1 tick, matching vm.clj's ticks-per-second
    (is (some? (vm/advance-state graph {:age 0.0 :input {}}
                                 {:events [] :delta-seconds one-tick}))
        "age 0 -> 1 of a 2-tick lifespan: still alive")
    (is (nil? (vm/advance-state graph {:age 1.0 :input {}}
                                {:events [] :delta-seconds one-tick}))
        "age 1 -> 2 of a 2-tick lifespan: dies exactly on schedule")))

(deftest advance-state-never-ends-a-graph-with-no-declared-lifespan
  (testing "matches :session lifecycle -- persists until an explicit :destroy signal"
    (let [graph {:component :vfx/channel-arc :mode :good}
          state {:age 100000.0 :input {}}]
      (is (some? (vm/advance-state graph state {:events [] :delta-seconds 1.0}))))))

(deftest advance-state-honors-a-bare-leaf-roots-own-life-ticks
  (testing "matches ray_fan_transient.edn's shape: no timeline wrapper, root has :life-ticks"
    (let [graph {:component :vfx/ray-fan :life-ticks 5}
          state {:age 4.0 :input {}}]
      (is (some? (vm/advance-state graph state {:events [] :delta-seconds 0.0})))
      (is (nil? (vm/advance-state graph (assoc state :age 5.0) {:events [] :delta-seconds 0.0}))))))
