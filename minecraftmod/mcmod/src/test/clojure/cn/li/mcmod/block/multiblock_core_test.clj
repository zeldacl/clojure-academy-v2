(ns cn.li.mcmod.block.multiblock-core-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.multiblock-core :as mb]
            [cn.li.mcmod.platform.be :as pbe]
            [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]))

(defrecord BlockPosStub [x y z]
  pos/IBlockPos
  (pos-x [_] x)
  (pos-y [_] y)
  (pos-z [_] z))

(defn- test-pos [x y z]
  (->BlockPosStub x y z))

(defn- controller-parts-opts []
  {:physical {:material :metal
              :hardness 5.0
              :resistance 10.0
              :requires-tool true
              :harvest-tool :pickaxe}
   :multi-block {:size {:width 2 :height 1 :depth 1}
                 :origin {:x 0 :y 0 :z 0}
                 :mode :controller-parts
                 :controller-block-id "ctl-test"
                 :part-block-id "part-test"}})

(defn- register-controller-and-part! []
  (bdsl/register-block! (bdsl/create-block-spec "ctl-test" (controller-parts-opts)))
  (bdsl/register-block! (bdsl/create-block-spec "part-test" (controller-parts-opts))))

(defn- reset-blocks! [f]
  (registry-core/reset-state! (bdsl/block-registry) {})
  (f)
  (registry-core/reset-state! (bdsl/block-registry) {}))

(use-fixtures :each reset-blocks!)

(deftest precheck-controller-place-occupied-test
  (register-controller-and-part!)
  (let [master (test-pos 10 0 0)]
    (binding [pos/*position-factory* (fn [x y z] (->BlockPosStub x y z))
              world/*world-get-block-state-fn*
              (fn [_w p]
                (when (= [11 0 0] [(pos/pos-x p) (pos/pos-y p) (pos/pos-z p)])
                  :solid))]
      (is (= {:cancel-place? true :reason :multiblock-space-occupied}
             (mb/precheck-controller-place {:world :w :pos master :block-id "ctl-test"}))))))

(deftest precheck-non-controller-yields-nil-test
  (is (nil? (mb/precheck-controller-place {:world :w :pos (test-pos 0 0 0) :block-id "not-a-controller"}))))

(deftest resolve-controller-pos-part-from-be-state-test
  (register-controller-and-part!)
  (binding [pos/*position-factory* (fn [x y z] (->BlockPosStub x y z))]
    (with-redefs [world/world-get-tile-entity* (constantly :fake-be)
                  pbe/get-custom-state (constantly {:controller-pos-x 5
                                                    :controller-pos-y 6
                                                    :controller-pos-z 7})]
      (let [resolved (mb/resolve-controller-pos {:world :w :pos (test-pos 9 9 9) :block-id "part-test"})]
        (is (= [5 6 7] [(pos/pos-x resolved) (pos/pos-y resolved) (pos/pos-z resolved)]))))))

(deftest route-to-controller-context-rewrites-ctx-test
  (register-controller-and-part!)
  (binding [pos/*position-factory* (fn [x y z] (->BlockPosStub x y z))]
    (with-redefs [world/world-get-tile-entity* (constantly :fake-be)
                  pbe/get-custom-state (constantly {:controller-pos-x 10
                                                    :controller-pos-y 0
                                                    :controller-pos-z 0})]
      (let [r (mb/route-to-controller-context {:world :w
                                               :pos (test-pos 1 2 3)
                                               :block-id "part-test"})]
        (is (= "part-test" (:original-block-id r)))
        (is (= "ctl-test" (:block-id r)))
        (is (= [10 0 0] [(pos/pos-x (:pos r)) (pos/pos-y (:pos r)) (pos/pos-z (:pos r))]))))))
