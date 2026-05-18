(ns cn.li.ac.ability.client.render-util-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.client.render-util :as ru]))

(deftest billboard-beam-ops-builds-standard-primitive
  (testing "outer quad, inner quad, and center line use the shared beam geometry"
    (let [start {:x 0.0 :y 0.0 :z 0.0}
          end {:x 0.0 :y 0.0 :z 1.0}
          cam-pos {:x 1.0 :y 0.0 :z 0.0}
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
              :texture "minecraft:textures/entity/beacon_beam.png"
              :p0 {:x 0.0 :y 0.2 :z 0.0}
              :p1 {:x 0.0 :y -0.2 :z 0.0}
              :p2 {:x 0.0 :y -0.2 :z 1.0}
              :p3 {:x 0.0 :y 0.2 :z 1.0}
              :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
              :color outer}
             (first ops)))
      (is (= {:kind :quad
              :texture "minecraft:textures/entity/beacon_beam.png"
              :p0 {:x 0.0 :y 0.1 :z 0.0}
              :p1 {:x 0.0 :y -0.1 :z 0.0}
              :p2 {:x 0.0 :y -0.1 :z 1.0}
              :p3 {:x 0.0 :y 0.1 :z 1.0}
              :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
              :color inner}
             (second ops)))
      (is (= {:kind :line :p1 start :p2 end :color line}
             (nth ops 2))))))