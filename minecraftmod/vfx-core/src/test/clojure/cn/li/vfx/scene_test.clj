(ns cn.li.vfx.scene-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.scene :as scene]))

(def ^:private arc-strike-scene
  "{:ability :arc-strike-scene
    :do [(beam {:start ?start :end ?end :grow-ticks 4})
         (ring {:center ?start :radius 1.0 :segments 16})
         (finish {:outcome :performed})]}")

(deftest compiles-against-the-scene-vocabulary-test
  (let [ir (scene/compile-doc! arc-strike-scene {:start :vec3 :end :vec3})]
    (is (= :arc-strike-scene (:id ir)))))

(deftest samples-real-ops-each-frame-test
  (let [ir (scene/compile-doc! arc-strike-scene {:start :vec3 :end :vec3})
        program (scene/compile-program ir)
        input {:capabilities {:start {:x 0.0 :y 1.0 :z 0.0} :end {:x 0.0 :y 1.0 :z 5.0}
                              :age 0.0 :progress 0.0}}
        ops (scene/sample! program input)]
    (testing "two ops sampled, in declared order"
      (is (= 2 (count ops)))
      (is (= :beam (:kind (first ops))))
      (is (= :ring (:kind (second ops)))))
    (testing "capability values flow through into the constructed op"
      (is (= {:x 0.0 :y 1.0 :z 0.0} (:start (first ops))))
      (is (= {:x 0.0 :y 1.0 :z 5.0} (:end (first ops))))
      (is (= 4 (:grow-ticks (first ops)))))
    (testing "sampling has no side effects -- resampling the same input is idempotent"
      (is (= ops (scene/sample! program input))))))

(deftest each-effect-declares-its-own-user-capability-types-test
  (testing "a scene using an undeclared ?capability is a real compile error,
            same as combat's ?caster/eye -- catches an author typo instead
            of silently reading nil at sample time"
    (let [doc "{:ability :bad :do [(beam {:start ?nope :end ?nope :grow-ticks 0}) (finish {:outcome :performed})]}"]
      (is (thrown? clojure.lang.ExceptionInfo (scene/compile-doc! doc {}))))))

(deftest v3-reference-bridge-preserves-namespaces-and-paths-test
  (let [document {:schema :ac/vfx-v3
                  :id :namespaced-reference
                  :lifecycle {:mode :session}
                  :inputs {:caster/eye {:type :vec3}
                           :style {:type :any}}
                  :system {:render [{:nid :n/render-root
                                     :component :beam
                                     :inputs {:start {:nid :n/render-start
                                                      :ref [:context :caster/eye]}
                                                     :end {:nid :n/render-end
                                                           :ref [:context :style :end]}}}
                                    {:nid :n/render-finish
                                     :flow :finish
                                     :result {:outcome :performed}}]}}
        program (scene/compile-v3-document! document)
        ops (scene/sample! program {:capabilities
                                    {:caster/eye {:x 1.0 :y 2.0 :z 3.0}
                                                  :style {:end {:x 4.0 :y 5.0 :z 6.0}}
                                                  :age 0.0 :progress 0.0}})]
    (is (= {:x 1.0 :y 2.0 :z 3.0} (:start (first ops))))
    (is (= {:x 4.0 :y 5.0 :z 6.0} (:end (first ops))))))
