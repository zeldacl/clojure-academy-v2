(ns cn.li.ac.vfx.billboard-session-test
  "Regression coverage for the Railgun charge billboard's declarative V4 path."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cn.li.vfx.scene :as scene]))

(deftest billboard-session-emits-animated-particle-description-test
  (let [resource (io/resource "ac/vfx-v4/billboard-session.edn")
        program (scene/compile-v4-document! (edn/read-string (slurp resource)))
        [op] (scene/sample!
              program
              {:capabilities {:anchor {:x 1.0 :y 2.0 :z 3.0}
                              :duration-ticks 64
                              :texture-pattern "academy:textures/effects/arc_burst/%d.png"
                              :frame-count 40
                              :frame-duration-ms 40
                              :half-size 0.4
                              :age 2.0
                              :progress 0.0
                              :seed 1
                              :source-player-id "owner"}})]
    (is (= :emitter (:kind op)))
    (is (= {:texture "academy:textures/effects/arc_burst/%d.png"
            :life-ticks 64
            :size 0.4
            :frame-count 40
            :frame-duration-ms 40
            :age 2.0}
           (:particle op)))))
