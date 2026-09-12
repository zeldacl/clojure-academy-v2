(ns cn.li.ac.vfx.railgun-render-pipeline-test
  "Headless regression for the complete V4 Railgun visual path."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cn.li.platform.neutral.vfx-render-plan :as render-plan]
            [cn.li.vfx.frame :as frame]
            [cn.li.vfx.scene :as scene]))

(defn- load-v4 [path]
  (edn/read-string (slurp (or (io/resource path)
                              (throw (ex-info "V4 resource missing" {:path path}))))))

(def ^:private beam-caps
  {:start {:x 1.0 :y 2.0 :z 3.0}
   :end {:x 1.0 :y 2.0 :z 28.0}
   :beam-at 0 :arc-at 3 :fade-at 30
   :fade-from-tick 30 :fade-to-tick 50
   :fade-from-alpha 1.0 :fade-to-alpha 0.0 :grow-ticks 3
   :layers [{:shape :tube :radius 0.13
             :texture "academy:textures/effects/railgun/tile.png"
             :color [236 170 93 60]}
            {:shape :tube :radius 0.09
             :texture "academy:textures/effects/solid.png"
             :color [241 240 222 200]}
            {:shape :line :width 0.02 :color [165 230 255 160]}]
   :ring-radius {:from 0.34 :to 0.34}
   :ring-segments 12 :ring-color [188 252 238 180]
   :arc-pattern :weak :seed 42 :arc-life-ticks 30
   :duration-ticks 50 :age 0.0 :progress 0.0})

(deftest railgun-beam-and-charge-reach-render-plan-test
  (testing "release beam survives V4 sample -> Java ABI -> geometry plan"
    (let [program (scene/compile-v4-document!
                   (load-v4 "ac/vfx-v4/beam-arc-fade.edn"))
          [beam-op] (scene/sample! program {:capabilities beam-caps})
          java-frame (frame/->java-frame 1 0 {[:railgun]
                                               {:scene [beam-op] :emitters []}})
          batch (first (.batches java-frame))
          plan (render-plan/neutral-op->plan
                (.payload batch)
                {:player-yaw-rad 0.0 :player-pitch-rad 0.0})]
      (is (= :beam (:kind beam-op)))
      (is (= "quad" (.primitive batch)))
      (is (seq (:ops plan)))))
  (testing "charge session becomes a concrete animated billboard quad"
    (let [program (scene/compile-v4-document!
                   (load-v4 "ac/vfx-v4/billboard-session.edn"))
          [emitter-op] (scene/sample!
                        program
                        {:capabilities {:anchor {:x 1.0 :y 2.0 :z 3.0}
                                        :duration-ticks 64
                                        :texture-pattern "academy:textures/effects/arc_burst/%d.png"
                                        :frame-count 40 :frame-duration-ms 40
                                        :half-size 0.4 :age 0.0 :progress 0.0
                                        :seed 42 :source-player-id "owner"}})
          java-frame (frame/->java-frame 1 0 {[:railgun]
                                               {:scene [emitter-op] :emitters []}})
          batch (first (.batches java-frame))
          plan (render-plan/neutral-op->plan
                (.payload batch)
                {:player-yaw-rad 0.0 :player-pitch-rad 0.0})]
      (is (= :emitter (:kind emitter-op)))
      (is (= "quad" (.primitive batch)))
      (is (seq (:ops plan))))))
