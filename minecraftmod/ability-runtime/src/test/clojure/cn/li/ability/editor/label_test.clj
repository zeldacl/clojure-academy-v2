(ns cn.li.ability.editor.label-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.label :as label]))

(deftest short-values-pass-through-unchanged-test
  (is (= "hello" (label/ellipsize "hello" 500.0)))
  (is (= "3" (label/ellipsize 3 500.0))))

(deftest long-values-are-shortened-with-a-trailing-ellipsis-test
  (let [long (apply str (repeat 200 "x"))
        clipped (label/ellipsize long 40.0)]
    (is (< (count clipped) (count long)))
    (is (.endsWith ^String clipped "..."))))

(deftest blank-and-nil-values-are-never-truncated-test
  (is (= "" (label/ellipsize nil 1.0)))
  (is (= "" (label/ellipsize "" 1.0)))
  (is (= "   " (label/ellipsize "   " 1.0))))

(deftest never-exceeds-the-given-width-budget-test
  ;; Widths stay >= the "..." suffix's own floor (3 chars * 4.8px/char in
  ;; the headless fallback estimate) -- below that floor there is no
  ;; possible truncation that fits, and the function returns bare "..."
  ;; rather than something even shorter and less recognizable.
  (let [measure-fallback (fn [s] (* 4.8 (count s)))]
    (doseq [text ["a" "medium length label" (apply str (repeat 50 "wide"))]
            width [20.0 40.0 100.0]]
      (let [clipped (label/ellipsize text width)]
        (is (<= (measure-fallback clipped) (+ width 0.001))
            (str "over budget: " (pr-str clipped) " at width " width))))))
