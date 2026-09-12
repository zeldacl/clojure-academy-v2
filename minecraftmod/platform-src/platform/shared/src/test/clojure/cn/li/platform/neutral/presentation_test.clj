(ns cn.li.platform.neutral.presentation-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.platform.neutral.arc-geometry :as arc-geometry]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.platform.neutral.vfx-render-plan :as vfx-plan]))

(deftest coalesce-frame-id-groups-calls-within-one-real-frame
  (testing "a second call microseconds later stays on the same frame id"
    (let [[same? next-nanos] (presentation/coalesce-frame-id 1000500 1000000)]
      (is (true? same?))
      (is (= 1000000 next-nanos))))
  (testing "a call past the coalesce window starts a new frame id"
    (let [[same? next-nanos] (presentation/coalesce-frame-id
                                (+ 1000000 presentation/frame-coalesce-window-nanos 1)
                                1000000)]
      (is (false? same?))
      (is (= (+ 1000000 presentation/frame-coalesce-window-nanos 1) next-nanos))))
  (testing "exactly at the window boundary is a new frame (half-open window)"
    (let [[same? _] (presentation/coalesce-frame-id
                       (+ 1000000 presentation/frame-coalesce-window-nanos)
                       1000000)]
      (is (false? same?)))))

(deftest typed-vfx-fallback-never-returns-an-empty-plan
  (let [line-plan (vfx-plan/neutral-op->plan
                   {:operation :draw-batch
                    :primitive :typed-vfx
                    :geometry {:kind :typed-vfx
                               :fields {:start [0.0 0.0 0.0]
                                        :end [1.0 0.0 0.0]}}
                    :material {}})
        marker-plan (vfx-plan/neutral-op->plan
                     {:operation :draw-batch
                      :primitive :typed-vfx
                      :geometry {:kind :typed-vfx :fields {}}
                      :material {}})]
    (is (= :line (:kind (first (:ops line-plan)))))
    (is (= 1 (count (:ops line-plan))))
    (is (= :quad (:kind (first (:ops marker-plan)))))
    (is (= 1 (count (:ops marker-plan))))))

(deftest arc-geometry-expands-to-textured-quad-strip
  "Regression: main arc-gen drew zigzag EntityArc quads. V4 :arc leaf must
   expand through the neutral render plan into a non-empty quad strip."
  (let [plan (vfx-plan/neutral-op->plan
              {:operation :draw-batch
               :primitive :quad
               :geometry {:kind :arc
                          :start {:x 0.0 :y 1.0 :z 0.0}
                          :end {:x 0.0 :y 1.0 :z 8.0}
                          :pattern :weak
                          :seed 7
                          :life-ratio 0.2}
               :material {:texture "academy:textures/effects/arc/line_segment.png"
                          :alpha 1.0
                          :color [255 255 255 255]}})
        ops (:ops plan)]
    (is (<= 8 (count ops)))
    (is (every? #(= :quad (:kind %)) ops))
    (is (every? #(= "academy:textures/effects/arc/line_segment.png" (:texture %)) ops))))

(deftest arc-hand-origin-shifts-start-in-first-person
  "Main arc-gen used ViewOptimize.fix so the bolt leaves the hand, not the eye."
  (let [view-ctx {:player-uuid "p1" :first-person? true}
        base {:operation :draw-batch
              :primitive :quad
              :geometry {:kind :arc
                         :start {:x 0.0 :y 2.0 :z 0.0}
                         :end {:x 0.0 :y 2.0 :z 10.0}
                         :pattern :weak
                         :seed 1
                         :hand-origin? true
                         :source-player-id "p1"
                         :life-ratio 0.0}
              :material {:alpha 1.0 :color [255 255 255 255]}}
        unshifted (vfx-plan/neutral-op->plan base)
        shifted (vfx-plan/neutral-op->plan base view-ctx)
        p0-un (:p0 (first (:ops unshifted)))
        p0-sh (:p0 (first (:ops shifted)))]
    (is (not= (.-y p0-un) (.-y p0-sh))
        "first-person hand-origin must change the bolt's world Y")))

(deftest bolt-reshapes-every-ttl-tick
  "Main EntityArc texWiggle: zigzag reseeds from (seed, remaining-ttl) each tick."
  (let [base {:operation :draw-batch
              :primitive :quad
              :geometry {:kind :arc
                         :start {:x 0.0 :y 1.0 :z 0.0}
                         :end {:x 0.0 :y 1.0 :z 8.0}
                         :pattern :weak
                         :seed 42
                         :age 0
                         :arc-life-ticks 8
                         :life-ratio 0.0}
              :material {:alpha 1.0 :color [255 255 255 255]}}
        ;; Pin visibility so reshape asserts aren't seed/Markov dependent.
        ops0 (with-redefs [arc-geometry/arc-visible? (constantly true)]
               (:ops (vfx-plan/neutral-op->plan base)))
        ops1 (with-redefs [arc-geometry/arc-visible? (constantly true)]
               (:ops (vfx-plan/neutral-op->plan
                      (assoc-in base [:geometry :age] 1))))]
    (is (seq ops0))
    (is (seq ops1))
    (is (not= (:p0 (first ops0)) (:p0 (first ops1)))
        "zigzag path must differ when remaining ttl changes")))

(deftest arc-show-hide-chain-follows-upstream-markov
  "Main showWiggle/hideWiggle 0.2/0.2 Markov — seed 0's Random sequence.
   Argument is remaining ttl (counting down), not age-up."
  (let [pattern {:show-wiggle 0.2 :hide-wiggle 0.2}]
    (is (true? (arc-geometry/arc-visible? pattern 0 5)))
    (is (true? (arc-geometry/arc-visible? pattern 0 11)))
    (is (false? (arc-geometry/arc-visible? pattern 0 12)))
    (is (true? (arc-geometry/arc-visible? pattern 0 13)))
    (is (false? (arc-geometry/arc-visible? pattern 0 14)))
    (is (true? (arc-geometry/arc-visible? pattern 0 20)))))

(deftest triple-bolts-cover-single-bolt-markov-gaps
  "Main ArcGen spawns 3 EntityArcs. Seed 5 alone is visible for only 1 of 8
   remaining-ttl ticks; with bolt-count 3 the cast stays lit across the life."
  (let [base {:kind :arc
              :start {:x 0.0 :y 1.0 :z 0.0}
              :end {:x 0.0 :y 1.0 :z 8.0}
              :pattern :weak
              :seed 5
              :arc-life-ticks 8
              :life-ratio 0.0}
        mat {:alpha 1.0 :color [255 255 255 255]}
        lit? (fn [bolt-count age]
               (seq (arc-geometry/arc-quad-ops
                     (assoc base :bolt-count bolt-count :age age)
                     mat nil)))
        single-lit (count (filter #(lit? 1 %) (range 8)))
        triple-lit (count (filter #(lit? 3 %) (range 8)))]
    (is (= 1 single-lit) "seed 5 single bolt: one visible tick (Markov gap)")
    (is (>= triple-lit 6) "three bolts fill most of the arc-life window")))
(deftest direct-host-bypasses-lifecycle-map-on-render-path
  (let [lifecycle-lookups (atom 0)
        api {:frame! (fn [_frame-id _delta _width _height] :frame)}]
    (presentation/reset-host-for-test!)
    (try
      (presentation/install-host! api)
      (with-redefs [cn.li.mcbase.presentation.host-lifecycle/host-api
                    (fn [& _] (swap! lifecycle-lookups inc))]
        (is (= :frame (presentation/frame! 1 0.05 800 600)))
        (is (= {:host-id :presentation :stage :hud :frame :frame}
               (presentation/dispatch-stage-with-context!
                :hud 1 0.05 800 600 nil)))
        (is (zero? @lifecycle-lookups)))
      (finally
        (presentation/reset-host-for-test!)))))
(deftest beam-geometry-expands-to-crossed-textured-quads
  (let [plan (vfx-plan/neutral-op->plan
               {:operation :draw-batch
                :primitive :quad
                :geometry {:kind :beam
                           :start {:x 0.0 :y 1.0 :z 0.0}
                           :end {:x 0.0 :y 1.0 :z 8.0}}
                :material {:alpha 1.0
                           :layers [{:shape :tube :radius 0.13
                                     :texture "academy:textures/effects/glow_line.png"
                                     :color [236 170 93 60]}
                                    {:shape :tube :radius 0.09
                                     :texture "academy:textures/effects/solid.png"
                                     :color [241 240 222 200]}]}})
        ops (:ops plan)]
    (is (= 4 (count ops)))
    (is (every? #(= :quad (:kind %)) ops))
    (is (= #{"academy:textures/effects/glow_line.png"
             "academy:textures/effects/solid.png"}
           (set (map :texture ops))))))
