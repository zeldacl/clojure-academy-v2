(ns cn.li.ac.ability.client.beam-ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]))

(deftest fading-beam-style-composes-rgb-alpha-test
  (let [start {:x 0.0 :y 0.0 :z 0.0}
        end {:x 0.0 :y 0.0 :z 1.0}
        cam-pos {:x 1.0 :y 0.0 :z 0.0}
        beam {:start start :end end :ttl 5 :max-ttl 10 :is-reflect? true}
        ops (beam-ops/fading-beam-ops
              cam-pos
              beam
              {:width (fn [_ life] life)
               :core-ratio 0.5
               :outer-rgb {:r 1 :g 2 :b 3}
               :outer-alpha (fn [_ life] (+ 10 (* 100 life)))
               :inner-rgb {:r 4 :g 5 :b 6}
               :inner-alpha 99
               :line-rgb (fn [b _] (if (:is-reflect? b)
                                      {:r 7 :g 8 :b 9}
                                      {:r 0 :g 0 :b 0}))
               :line-alpha (fn [_ life] (* 200 life))})]
    (testing "uses the shared billboard primitive shape"
      (is (= 3 (count ops)))
      (is (= [:quad :quad :line] (mapv :kind ops))))
    (testing "resolves RGB + alpha constants and callbacks"
      (is (= {:r 1 :g 2 :b 3 :a 60} (:color (first ops))))
      (is (= {:r 4 :g 5 :b 6 :a 99} (:color (second ops))))
      (is (= {:r 7 :g 8 :b 9 :a 100} (:color (nth ops 2)))))))

(deftest direct-beam-style-resolves-static-colors-test
  (let [start {:x 0.0 :y 0.0 :z 0.0}
        end {:x 0.0 :y 1.0 :z 0.0}
        cam-pos {:x 1.0 :y 0.0 :z 0.0}
        ops (beam-ops/beam-ops
              cam-pos
              start
              end
              {:width 0.25
               :core-width 0.1
               :outer-rgb {:r 10 :g 20 :b 30}
               :outer-alpha 40
               :inner-color {:r 50 :g 60 :b 70 :a 80}})]
    (is (= 2 (count ops)))
    (is (= {:r 10 :g 20 :b 30 :a 40} (:color (first ops))))
    (is (= {:r 50 :g 60 :b 70 :a 80} (:color (second ops))))))

(deftest trajectory-glow-helpers-match-legacy-shape-test
  (let [p0 {:x 0.0 :y 0.0 :z 0.0}
        p1 {:x 1.0 :y 0.0 :z 0.0}
        p2 {:x 1.0 :y 1.0 :z 0.0}
        p3 {:x 0.0 :y 1.0 :z 0.0}
        color (beam-ops/rgba {:r 255 :g 51 :b 51} (beam-ops/fade-alpha 0))
        op (beam-ops/glow-line-quad-op p0 p1 p2 p3 color)]
    (is (= 178 (:a color)))
    (is (= :quad (:kind op)))
    (is (= beam-ops/default-glow-line-texture (:texture op)))
    (is (= color (:color op)))))
