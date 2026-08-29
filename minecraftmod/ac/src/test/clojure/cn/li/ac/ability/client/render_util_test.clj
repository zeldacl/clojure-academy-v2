(ns cn.li.ac.ability.client.render-util-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.rv3 :as v3]))

(deftest billboard-beam-ops-builds-standard-primitive
  (testing "outer quad, inner quad, and center line use the shared beam geometry"
    (let [start (v3/v3 0.0 0.0 0.0)
          end (v3/v3 0.0 0.0 1.0)
          cam-pos (v3/v3 1.0 0.0 0.0)
          outer {:r 1 :g 2 :b 3 :a 4}
          inner {:r 5 :g 6 :b 7 :a 8}
          line {:r 9 :g 10 :b 11 :a 12}
          ops (ru/billboard-beam-ops cam-pos start end
                {:width 0.2
                 :core-ratio 0.5
                 :outer-color outer
                 :inner-color inner
                 :line-color line})]
      (is (= 3 (count ops)))
      (is (= {:kind :quad
              :texture "academy:textures/effects/arc.png"
              :p0 (v3/v3 0.0 0.2 0.0)
              :p1 (v3/v3 0.0 -0.2 0.0)
              :p2 (v3/v3 0.0 -0.2 1.0)
              :p3 (v3/v3 0.0 0.2 1.0)
              :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
              :color outer}
             (first ops)))
      (is (= {:kind :quad
              :texture "academy:textures/effects/arc.png"
              :p0 (v3/v3 0.0 0.1 0.0)
              :p1 (v3/v3 0.0 -0.1 0.0)
              :p2 (v3/v3 0.0 -0.1 1.0)
              :p3 (v3/v3 0.0 0.1 1.0)
              :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
              :color inner}
             (second ops)))
      (is (= {:kind :line :p1 start :p2 end :color line}
             (nth ops 2))))))

(deftest zigzag-arc-junctions-are-watertight-test
  ;; Upstream ArcFactory.handleSegment carries lastDir — the previous
  ;; segment's width axis — into the next quad, so adjacent quads share one
  ;; axis at the junction and the strip stays continuous through every kink.
  ;; Independent per-segment billboards meet at the vertex but fan apart
  ;; along their own axes, notching the outside of each bend; ThunderBolt's
  ;; wide, strongly-jagged strongArc (width 0.3) made those notches read as
  ;; the arc breaking into separate segments.
  (let [vertices [{:pos (v3/v3 0.0 0.0 0.0) :u 0.0 :v 0.0}
                  {:pos (v3/v3 0.0 1.0 0.0) :u 0.5 :v 0.0}
                  {:pos (v3/v3 1.0 1.0 0.0) :u 1.0 :v 0.0}]
        pattern {:width 0.3 :core-ratio 0.4 :fork-count 0
                 :color-outer {:r 130 :g 210 :b 255}
                 :color-inner {:r 230 :g 245 :b 255}
                 :color-line {:r 200 :g 240 :b 255}}
        ops (ru/zigzag-arc-ops (v3/v3 0.5 0.5 1.0) vertices pattern
                               {:life-ratio 0.5 :wiggle-phase 0.0 :effective-wiggle 0.0})
        [o0 i0 _ o1 i1 _] ops
        axis-0 (ru/beam-right-axis (v3/v3 0.0 0.0 0.0) (v3/v3 0.0 1.0 0.0)
                                   (v3/v3 0.5 0.5 1.0))
        axis-1 (ru/beam-right-axis (v3/v3 0.0 1.0 0.0) (v3/v3 1.0 1.0 0.0)
                                   (v3/v3 0.5 0.5 1.0))]
    (is (not= axis-0 axis-1) "the two segments genuinely fan apart")
    (is (= (:p2 o0) (:p1 o1)) "outer quads share the junction edge")
    (is (= (:p3 o0) (:p0 o1)))
    (is (= (:p2 i0) (:p1 i1)) "core quads share the junction edge")
    (is (= (:p3 i0) (:p0 i1)))))
