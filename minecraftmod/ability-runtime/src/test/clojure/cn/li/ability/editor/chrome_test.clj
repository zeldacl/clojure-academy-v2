(ns cn.li.ability.editor.chrome-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ability.editor.chrome :as chrome]))

(def ^:private base
  {:design-width 560.0 :design-height 380.0
   :palette-open? true :palette-open-w 160.0
   :inspector-open? true :inspector-open-w 140.0
   :diagnostic-count 0})

(deftest panel-widths-sum-to-design-width-test
  (doseq [palette-open? [true false]
          inspector-open? [true false]]
    (let [g (chrome/panel-geometry (assoc base :palette-open? palette-open?
                                                :inspector-open? inspector-open?))]
      (is (= (:design-width base)
             (+ (:palette-w g) (:stage-w g) (:inspector-w g)))
          (str "palette-open?=" palette-open? " inspector-open?=" inspector-open?)))))

(deftest closed-panel-width-is-zero-test
  (let [g (chrome/panel-geometry (assoc base :palette-open? false :inspector-open? false))]
    (is (zero? (:palette-w g)))
    (is (zero? (:inspector-w g)))
    (is (= (:design-width base) (:stage-w g)))))

(deftest open-panel-width-matches-requested-open-w-test
  (let [g (chrome/panel-geometry base)]
    (is (= 160.0 (:palette-w g)))
    (is (= 140.0 (:inspector-w g)))))

(deftest diagnostics-height-is-zero-when-count-is-zero-test
  (let [g (chrome/panel-geometry (assoc base :diagnostic-count 0))]
    (is (zero? (:diagnostics-h g)))))

(deftest diagnostics-height-is-open-when-count-is-positive-test
  (let [g (chrome/panel-geometry (assoc base :diagnostic-count 3))]
    (is (= chrome/diagnostics-open-h (:diagnostics-h g)))))

(deftest body-height-accounts-for-header-footer-and-diagnostics-test
  (testing "no diagnostics"
    (let [g (chrome/panel-geometry (assoc base :diagnostic-count 0))]
      (is (= (- (:design-height base) chrome/header-h chrome/footer-h) (:body-h g)))))
  (testing "with diagnostics open"
    (let [g (chrome/panel-geometry (assoc base :diagnostic-count 1))]
      (is (= (- (:design-height base) chrome/header-h chrome/footer-h chrome/diagnostics-open-h)
             (:body-h g))))))

(deftest stage-width-grows-as-panels-collapse-test
  (let [both-open (:stage-w (chrome/panel-geometry base))
        palette-closed (:stage-w (chrome/panel-geometry (assoc base :palette-open? false)))
        both-closed (:stage-w (chrome/panel-geometry (assoc base :palette-open? false :inspector-open? false)))]
    (is (< both-open palette-closed))
    (is (< palette-closed both-closed))))

(deftest never-produces-negative-panel-sizes-test
  (let [g (chrome/panel-geometry {:design-width 50.0 :design-height 20.0
                                   :palette-open? true :palette-open-w 160.0
                                   :inspector-open? true :inspector-open-w 140.0
                                   :diagnostic-count 5})]
    (is (>= (:stage-w g) 0.0))
    (is (>= (:body-h g) 0.0))))
