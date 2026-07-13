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
              :texture "my_mod:textures/effects/arc.png"
              :p0 (v3/v3 0.0 0.2 0.0)
              :p1 (v3/v3 0.0 -0.2 0.0)
              :p2 (v3/v3 0.0 -0.2 1.0)
              :p3 (v3/v3 0.0 0.2 1.0)
              :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
              :color outer}
             (first ops)))
      (is (= {:kind :quad
              :texture "my_mod:textures/effects/arc.png"
              :p0 (v3/v3 0.0 0.1 0.0)
              :p1 (v3/v3 0.0 -0.1 0.0)
              :p2 (v3/v3 0.0 -0.1 1.0)
              :p3 (v3/v3 0.0 0.1 1.0)
              :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
              :color inner}
             (second ops)))
      (is (= {:kind :line :p1 start :p2 end :color line}
             (nth ops 2))))))
