(ns cn.li.ac.block.render-runtime-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.block.cat-engine.render :as cat-render]
            [cn.li.ac.block.machine.render-runtime :as machine-render-runtime]
            [cn.li.ac.block.wind-gen.render :as wind-render]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.block.wireless-matrix.render :as matrix-render]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.render :as render]))

(defn- fake-pos
  [x y z]
  {:x x :y y :z z})

(defn- isolated-runtime [cache-key & [initial]]
  (:runtime (machine-render-runtime/create-cache-runtime cache-key (or initial {}))))

(use-fixtures :each
  (fn [f]
    (machine-render-runtime/with-bound-runtime cat-render/*cat-engine-render-runtime*
                                               (isolated-runtime :rotor-cache)
      (machine-render-runtime/with-bound-runtime wind-render/*wind-gen-render-runtime*
                                                 (isolated-runtime :fan-rot-cache)
        (machine-render-runtime/with-bound-runtime matrix-render/*wireless-matrix-render-runtime*
                                                   (isolated-runtime :last-shield-hw-state nil)
          (f))))))

(deftest cat-engine-rotor-cache-runtime-isolation-test
  (let [runtime-b (isolated-runtime :rotor-cache)
        time* (atom 1.0)
        tile (fake-pos 1 2 3)]
    (with-redefs [pos/position-get-block-pos (fn [value] value)
                  pos/pos-x (fn [value] (:x value))
                  pos/pos-y (fn [value] (:y value))
                  pos/pos-z (fn [value] (:z value))
                  render/get-render-time (fn [] @time*)]
      (#'cat-render/next-rotation! tile 1.0)
      (reset! time* 1.1)
      (is (= 1.0
             (#'cat-render/next-rotation! tile 1.0)))
      (is (= {[1 2 3] {:t 1100 :rot 1.0}}
             (cat-render/rotor-cache-snapshot)))
      (machine-render-runtime/with-bound-runtime cat-render/*cat-engine-render-runtime* runtime-b
        (reset! time* 2.0)
        (#'cat-render/next-rotation! tile 2.0)
        (is (= {[1 2 3] {:t 2000 :rot 0.0}}
               (cat-render/rotor-cache-snapshot))))
      (is (= {[1 2 3] {:t 1100 :rot 1.0}}
             (cat-render/rotor-cache-snapshot))))))

(deftest wind-gen-fan-rotation-cache-runtime-isolation-test
  (let [runtime-b (isolated-runtime :fan-rot-cache)
        time* (atom 0.0)
        tile (fake-pos 4 5 6)]
    (with-redefs [pos/position-get-block-pos (fn [value] value)
                  pos/pos-x (fn [value] (:x value))
                  pos/pos-y (fn [value] (:y value))
                  pos/pos-z (fn [value] (:z value))
                  render/get-render-time (fn [] @time*)]
      (#'wind-render/update-fan-rotation! tile 60.0)
      (reset! time* 0.5)
      (is (= 30.0
             (#'wind-render/update-fan-rotation! tile 60.0)))
      (is (= {[4 5 6] {:t 0.5 :rot 30.0}}
             (wind-render/fan-rot-cache-snapshot)))
      (machine-render-runtime/with-bound-runtime wind-render/*wind-gen-render-runtime* runtime-b
        (reset! time* 1.0)
        (#'wind-render/update-fan-rotation! tile 120.0)
        (is (= {[4 5 6] {:t 1.0 :rot 0.0}}
               (wind-render/fan-rot-cache-snapshot))))
      (is (= {[4 5 6] {:t 0.5 :rot 30.0}}
             (wind-render/fan-rot-cache-snapshot))))))

(deftest wireless-matrix-render-runtime-isolation-test
  (let [runtime-b (isolated-runtime :last-shield-hw-state nil)]
    (with-redefs [matrix-logic/get-plate-count (fn [tile]
                                                 (if (= tile :matrix-a) 3 0))
                  matrix-logic/get-core-level (fn [tile]
                                                (if (= tile :matrix-a) 1 0))
                  render/get-render-time (fn [] 0.0)
                  pose/push-pose (fn [& _] nil)
                  pose/pop-pose (fn [& _] nil)
                  pose/translate (fn [& _] nil)
                  pose/apply-y-rotation (fn [& _] nil)
                  obj/render-part-consumer (fn [& _] nil)]
      (matrix-render/render-shields :matrix-a 0.0 :pose :vc 0 0)
      (is (= {:plate-count 3 :core-level 1 :active-plates 3}
             (matrix-render/last-shield-hw-state-snapshot)))
      (machine-render-runtime/with-bound-runtime matrix-render/*wireless-matrix-render-runtime* runtime-b
        (matrix-render/render-shields :matrix-b 0.0 :pose :vc 0 0)
        (is (= {:plate-count 0 :core-level 0 :active-plates 0}
               (matrix-render/last-shield-hw-state-snapshot))))
      (is (= {:plate-count 3 :core-level 1 :active-plates 3}
             (matrix-render/last-shield-hw-state-snapshot))))))
