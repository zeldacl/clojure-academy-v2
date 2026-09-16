(ns cn.li.platform.neutral.particle-appearance-test
  "An emitter particle's own colour, alpha and fade reach the quad it draws.

   They did not. :emitter's :particle parameter is a free-form :any map and
   this plan read six keys off it -- :age :frame-count :frame-duration-ms
   :scale :size :texture -- so an emitter drew its MATERIAL colour at full
   opacity and every appearance key content wrote was dropped between the
   scene op and the renderer.

   teleport-marker is the case that made it visible: its particle carries
   the pre-V4 tp_mark spec exactly (a green tint at alpha 153-204, fade-in
   5 / fade-out 20 over a 20-tick life, size 0.1-0.2) and what rendered was
   a white billboard that never faded.

   These assert the VALUE, not the shape. A quad op is a well-formed map
   either way -- that is precisely why the drop went unnoticed -- so each
   test compares against what the same particle produces without the key."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.platform.neutral.vfx-render-plan :as plan]))

(defn- emitter-op
  "One :emitter draw-batch through the neutral plan, returning its quads."
  [particle]
  (plan/neutral-op->plan
   {:operation :draw-batch
    :stage :world-translucent
    :primitive :quad
    :geometry {:kind :emitter :anchor [0.0 0.0 0.0] :particle particle}
    :material {:particle particle}}
   nil))

(defn- quad-color [plan]
  ;; neutral-op->plan returns {:ops [...]}, not the ops directly.
  (:color (first (filter #(= :quad (:kind %)) (:ops plan)))))

(deftest particle-color-reaches-the-quad-test
  (testing "the particle's own colour is used, not the material default"
    (is (= [0 255 0 255]
           (quad-color (emitter-op {:texture "t.png" :color [0 255 0 255]})))))
  (testing "a particle naming no colour still renders"
    (is (some? (quad-color (emitter-op {:texture "t.png"}))))))

(deftest particle-alpha-overrides-the-colour-alpha-test
  (testing "a scalar :alpha replaces the colour's own"
    (is (= [0 255 0 128]
           (quad-color (emitter-op {:texture "t.png" :color [0 255 0 255]
                                    :alpha 128})))))
  (testing "a {:min :max} range lands inside the range"
    (let [a (nth (quad-color (emitter-op {:texture "t.png" :color [0 255 0 255]
                                          :alpha {:min 153 :max 204}}))
                 3)]
      (is (<= 153 a 204))))
  (testing "and the same particle at the same anchor keeps the same alpha
            across frames -- a range re-rolled per frame would flicker"
    (let [at (fn [age] (nth (quad-color (emitter-op {:texture "t.png"
                                                    :alpha {:min 153 :max 204}
                                                    :age age}))
                            3))]
      (is (= (at 0.0) (at 7.0) (at 13.0))))))

(deftest fade-envelope-ramps-in-and-out-test
  (let [particle (fn [age] {:texture "t.png" :color [0 255 0 200] :age age
                            :life-ticks 20 :fade-in-ticks 5 :fade-out-ticks 10})
        alpha-at (fn [age] (nth (quad-color (emitter-op (particle age))) 3))]
    (testing "ramps in over :fade-in-ticks"
      (is (= 0 (alpha-at 0.0)))
      (is (< 0 (alpha-at 2.5) 200))
      (is (= 200 (alpha-at 5.0))))
    (testing "holds between the ramps"
      (is (= 200 (alpha-at 8.0))))
    (testing "ramps out over the last :fade-out-ticks of :life-ticks"
      (is (< (alpha-at 15.0) 200))
      (is (= 0 (alpha-at 20.0))))
    (testing "and a particle with no fade pair holds full alpha throughout"
      (let [flat (fn [age] (nth (quad-color (emitter-op {:texture "t.png"
                                                        :color [0 255 0 200]
                                                        :age age :life-ticks 20}))
                                3))]
        (is (= 200 (flat 0.0) (flat 10.0) (flat 19.0)))))))

(deftest colour-accepts-both-spellings-test
  ;; The material path uses {:r :g :b :a}; content writes [r g b a]. Both
  ;; reach this code, so both must resolve rather than one silently
  ;; falling back to white.
  (is (= [10 20 30 40]
         (quad-color (emitter-op {:texture "t.png" :color {:r 10 :g 20 :b 30 :a 40}}))))
  (is (= [10 20 30 40]
         (quad-color (emitter-op {:texture "t.png" :color [10 20 30 40]})))))
