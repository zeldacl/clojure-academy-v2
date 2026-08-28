(ns cn.li.presentation.compiler.artifact-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.compiler.artifact :as artifact]))

(deftest compiles-normalized-bindings-actions-and-semantics
  (let [compiled (artifact/compile-source
                   {:view/id :academy/test/example
                    :root {:type :column
                           :children [{:type :text
                                       :bind {:text [:state :title]}
                                       :semantics {:role :heading}}
                                      {:type :button
                                       :on {:activate :example/save}
                                       :semantics {:role :button}}]}}
                   "example.ui.edn")]
    (is (= :pui3 (:magic compiled)))
    (is (= 3 (:schema compiled)))
    (is (= [{:id 0 :path [:state :title]}] (:bindings compiled)))
    (is (= [{:id 0 :name :example/save}] (:actions compiled)))
    (is (= :column (get-in compiled [:nodes :type])))
    (is (= 2 (count (get-in compiled [:nodes :children]))))))

(deftest rejects-unsupported-primitive
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported primitive"
                        (artifact/compile-source
                         {:view/id :academy/test/bad
                          :root {:type :legacy-template}}
                         "bad.ui.edn"))))

(deftest canonicalization-is-deterministic
  (let [a (artifact/compile-source
           {:root {:type :rect :style {:z 1 :a 2}}
            :view/id :academy/test/hash}
           "a.ui.edn")
        b (artifact/compile-source
           {:view/id :academy/test/hash
            :root {:style {:a 2 :z 1} :type :rect}}
           "a.ui.edn")]
    (is (= (:source-hash a) (:source-hash b)))
    (is (= a b))))
