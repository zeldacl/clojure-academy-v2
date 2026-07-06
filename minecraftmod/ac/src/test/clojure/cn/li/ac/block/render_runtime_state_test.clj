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







(deftest register-client-renderer-init-resolves-symbol-test
       (let [registered* (atom nil)
                            invoked* (atom false)
                            target-sym 'cn.li.ac.block.render-runtime-state-test/fake-render-init]
              (with-redefs [clojure.core/requiring-resolve
                                                               (fn [sym]
                                                                      (case sym
                                                                             cn.li.mcmod.client.render.init/register-renderer-init-fn!
                                                                             (fn [f] (reset! registered* f))

                                                                             cn.li.ac.block.render-runtime-state-test/fake-render-init
                                                                             (fn [] (reset! invoked* true))

                                                                             nil))]
                     (machine-render-runtime/register-client-renderer-init! target-sym)
                     (is (fn? @registered*))
                     (@registered*)
                     (is (true? @invoked*)))))
