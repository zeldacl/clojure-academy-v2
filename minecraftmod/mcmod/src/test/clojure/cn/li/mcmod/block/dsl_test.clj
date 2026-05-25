(ns cn.li.mcmod.block.dsl-test
  "Unit tests for Block DSL"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.events :as block-events]
            [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]))

(defn- reset-block-registry! [f]
  (registry-core/reset-state! bdsl/block-registry {})
  (f)
  (registry-core/reset-state! bdsl/block-registry {}))

(use-fixtures :each reset-block-registry!)

(deftest create-block-spec-flat-syntax-test
  (testing "flat syntax maps into nested records"
    (let [spec (bdsl/create-block-spec "test-block"
                                       {:material :stone
                                        :hardness 2.0
                                        :resistance 5.0
                                        :light-level 7})]
      (is (= "test-block" (:id spec)))
      (is (= :stone (get-in spec [:physical :material])))
      (is (= 2.0 (get-in spec [:physical :hardness])))
      (is (= 5.0 (get-in spec [:physical :resistance])))
      (is (= 7 (get-in spec [:rendering :light-level]))))))

(deftest nested-syntax-precedence-test
  (testing "nested config takes precedence over top-level keys"
    (let [spec (bdsl/create-block-spec "nested-priority"
                                       {:material :wood
                                        :hardness 1.0
                                        :physical {:material :metal
                                                   :hardness 8.0}})]
      (is (= :metal (get-in spec [:physical :material])))
      (is (= 8.0 (get-in spec [:physical :hardness]))))))

(deftest register-and-lookup-test
  (testing "register-block! + get-block supports string and keyword ids"
    (let [spec (bdsl/create-block-spec "registry-block" {:material :stone})]
      (bdsl/register-block! spec)
      (is (= "registry-block" (:id (bdsl/get-block "registry-block"))))
      (is (= "registry-block" (:id (bdsl/get-block :registry-block))))
      (is (= #{"registry-block"} (set (bdsl/list-blocks)))))))

(deftest validate-block-spec-invalid-material-test
  (testing "invalid material is rejected"
    (let [spec (bdsl/create-block-spec "bad-material" {:material :not-a-material})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid material"
            (bdsl/validate-block-spec spec))))))

(deftest validate-multiblock-positions-test
  (testing "multi-block positions must include origin"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"must include origin"
          (bdsl/validate-multi-block-positions [{:x 1 :y 0 :z 0}]))))
  (testing "multi-block positions accept vector and map forms"
    (is (true? (bdsl/validate-multi-block-positions [[0 0 0] {:x 1 :y 0 :z 0}])))))

(deftest multiblock-position-helpers-test
  (testing "regular shape expands with relative coordinates"
    (let [positions (bdsl/calculate-multi-block-positions {:width 2 :height 1 :depth 1}
                                                          {:x 0 :y 0 :z 0})]
      (is (= 2 (count positions)))
      (is (some :is-origin? positions))
      (is (= #{0 1} (set (map :relative-x positions))))))
  (testing "normalize-positions shifts minimum corner to origin"
    (let [normalized (bdsl/normalize-positions [{:x 3 :y 5 :z 7}
                                                {:x 4 :y 5 :z 8}])]
      (is (= [{:x 0 :y 0 :z 0}
              {:x 1 :y 0 :z 1}]
             normalized)))))

(defrecord BlockPosStub [x y z]
  pos/IBlockPos
  (pos-x [_] x)
  (pos-y [_] y)
  (pos-z [_] z))

(deftest get-multi-block-master-pos-test
  (is (= {:x 4 :y 8 :z 2}
         (bdsl/get-multi-block-master-pos {:x 5 :y 10 :z 3}
                                          {:relative-x 1 :relative-y 2 :relative-z 1}))))

(deftest all-multi-block-positions-with-factory-test
  (binding [pos/*position-factory* (fn [x y z] (->BlockPosStub x y z))]
    (let [spec (bdsl/create-block-spec "mb"
                                       (bdsl/multi-block-template {:width 2 :height 1 :depth 1}))
          master (pos/create-block-pos 10 20 30)
          ps (bdsl/all-multi-block-positions master spec)]
      (is (= 2 (count ps)))
      (is (every? #(satisfies? pos/IBlockPos %) ps))
      (is (= #{[10 20 30] [11 20 30]}
             (set (map (fn [p] [(pos/pos-x p) (pos/pos-y p) (pos/pos-z p)]) ps)))))))

(deftest can-place-multi-block-binding-test
  (testing "all footprint cells empty"
    (binding [pos/*position-factory* (fn [x y z] (->BlockPosStub x y z))
              world/*world-get-block-state-fn* (constantly nil)]
      (let [spec (bdsl/create-block-spec "empty-mb"
                                         (bdsl/multi-block-template {:width 1 :height 1 :depth 1}))]
        (is (true? (bdsl/can-place-multi-block? :fake-world (pos/create-block-pos 0 0 0) spec))))))
  (testing "blocked cell fails placement"
    (binding [pos/*position-factory* (fn [x y z] (->BlockPosStub x y z))
              world/*world-get-block-state-fn*
              (fn [_w p]
                (when (= [1 0 0] [(pos/pos-x p) (pos/pos-y p) (pos/pos-z p)])
                  :blocked))]
      (let [spec (bdsl/create-block-spec "blocked-mb"
                                         (bdsl/multi-block-template {:width 2 :height 1 :depth 1}))]
        (is (false? (bdsl/can-place-multi-block? :fake-world (pos/create-block-pos 0 0 0) spec)))))))

(deftest template-and-merge-helpers-test
  (is (= 2 (:harvest-level (bdsl/ore-template 2))))
  (is (= :wood (:material (bdsl/wood-template))))
  (is (= 3 (:harvest-level (bdsl/metal-template 3))))
  (is (= :glass (:material (bdsl/glass-template))))
  (is (= 15 (:light-level (bdsl/light-block-template 15))))
  (is (true? (:multi-block? (bdsl/multi-block-template {:width 2 :height 1 :depth 1}))))
  (is (vector? (:multi-block-positions (bdsl/irregular-multi-block-template [{:x 1 :y 0 :z 0} {:x 0 :y 0 :z 0}]))))
  (is (pos? (count (bdsl/create-l-shape 2 2))))
  (is (pos? (count (bdsl/create-t-shape 3 2))))
  (is (pos? (count (bdsl/create-cross-shape 1))))
  (is (pos? (count (bdsl/create-pyramid-shape 3 2))))
  (is (pos? (count (bdsl/create-hollow-cube 2))))
  (is (= :stone (:material (bdsl/merge-templates (bdsl/wood-template) {:material :stone})))))

(deftest interaction-handlers-test
  ;; Handler fns are read from top-level keys on the spec map by block event helpers.
  (let [spec (-> (bdsl/create-block-spec "evt"
                                         {:material :stone
                                          :multi-block? true
                                          :multi-block-size {:width 1 :height 1 :depth 1}
                                          :events {:on-multi-block-break (fn [e] [:mb e])}})
                 (assoc :on-right-click (fn [e] [:right e])
                        :on-break (fn [e] [:break e])
                        :on-place (fn [e] [:place e])))]
    (is (= [:right :x] (block-events/handle-right-click spec :x)))
    (is (= [:break :y] (block-events/handle-break spec :y)))
    (is (= [:place :z] (block-events/handle-place spec :z)))
    (let [r (block-events/handle-multi-block-break spec {:pos :p})]
      (is (true? (:should-break-all r)))
      ;; `calculate-multi-block-positions` uses `for` for regular shapes → lazy seq, not vec.
      (is (sequential? (:positions r)))
      (is (pos? (count (:positions r)))))))

(deftest get-block-properties-test
  (let [spec (bdsl/create-block-spec "props"
              (merge (bdsl/metal-template 2)
                                            {:light-level 9}))]
    (is (= :metal (:material (bdsl/get-block-properties spec))))
    (is (= 9 (:light-level (bdsl/get-block-properties spec))))
    (is (true? (:requires-tool (bdsl/get-block-properties spec))))))
