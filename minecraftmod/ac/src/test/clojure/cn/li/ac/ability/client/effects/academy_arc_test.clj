(ns cn.li.ac.ability.client.effects.academy-arc-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.client.effects.academy-arc :as academy-arc]
            [cn.li.ac.ability.client.effects.rv3 :as v]))

(use-fixtures
 :each
 (fn [f]
   (academy-arc/reset-template-cache-for-test!)
   (try
     (f)
     (finally
       (academy-arc/reset-template-cache-for-test!)))))

(deftest upstream-template-banks-and-dimensions-test
  (let [charging (academy-arc/template-snapshot :charging)
        normal (academy-arc/template-snapshot :normal)
        thin (academy-arc/template-snapshot :thin)]
    (testing "ArcPatterns.chargingArc has 20 fixed 20-block templates"
      (is (= 20 (count charging)))
      (is (every? #(= 20.0 (:length %)) charging)))
    (testing "EntitySurroundArc generates ten mode-specific templates"
      (is (= 10 (count normal)))
      (is (= 10 (count thin)))
      (is (every? #(<= 3.0 (:length %) 4.0) normal))
      (is (every? #(<= 1.5 (:length %) 2.0) thin)))
    (testing "the high upstream branch factor produces recursive sub-lines"
      (is (every? #(> (:line-count %) 1) normal))
      (is (every? #(> (:segment-count %) 8) normal)))))

(deftest entity-arc-prefix-is-immediately-renderable-test
  (let [ops (academy-arc/entity-arc-ops
             (v/v3 0.0 1.6 0.0)
             (v/v3 0.0 1.6 0.0)
             (v/v3 0.0 1.6 15.0)
             0
             nil)]
    (is (seq ops))
    (is (every? #(= :current-charging/beam (:effect-part %)) ops))
    (is (every? #(= {:r 255 :g 255 :b 255}
                    (dissoc (:color %) :a))
                ops))))

(deftest surround-subarcs-use-client-tick-state-test
  (let [salt 1000
        initial (academy-arc/initial-surround-state :normal salt)
        advanced (reduce
                  (fn [state tick]
                    (academy-arc/tick-surround-state
                     state :normal salt tick))
                  initial
                  (range 1 11))
        ops (academy-arc/surround-arc-ops
             (v/v3 0.0 2.0 -3.0)
             {:x 0.5 :y 0.0 :z 0.5
              :width 1.0 :height 1.0 :depth 1.0}
             :normal
             advanced
             salt)]
    (is (= 6 (count (:sparks initial))))
    (is (every? (complement :draw?) (:sparks initial)))
    (is (= 6 (count (:sparks advanced))))
    (is (some :draw? (:sparks advanced)))
    (is (seq ops))
    (is (every? #(= :current-charging/surround (:effect-part %)) ops))))
