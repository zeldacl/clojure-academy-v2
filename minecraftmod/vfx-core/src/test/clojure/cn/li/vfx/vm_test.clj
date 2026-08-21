(ns cn.li.vfx.vm-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.vm :as vm]))

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
