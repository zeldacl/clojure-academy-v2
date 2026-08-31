(ns cn.li.presentation.compiler.artifact-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.compiler.artifact :as artifact]))

(deftest compiles-normalized-bindings-actions-and-semantics
  (let [compiled (artifact/compile-source
                   {:ui/schema 1
                    :view/id :academy/test/example
                    :root {:type :column
                           :children [{:type :text
                                       :bind {:text [:state :title]}
                                       :semantics {:role :heading}}
                                      {:type :button
                                       :on {:activate :example/save}
                                       :semantics {:role :button}}]}}
                   "example.ui.edn")]
    (is (= :pui4 (:magic compiled)))
    (is (= 4 (:schema compiled)))
    (is (= [{:id 0 :path [:state :title]}] (:bindings compiled)))
    (is (= [{:id 0 :name :example/save}] (:actions compiled)))
    (is (= :column (get-in compiled [:nodes :type])))
    (is (= 2 (count (get-in compiled [:nodes :children]))))))

(deftest expands-high-level-components-before-primitive-validation
  (let [compiled (artifact/compile-source
                   {:ui/schema 1
                    :view/id :academy/test/tree
                    :root {:type :tree-view
                           :key :tree
                           :props {:items [:state :items]
                                   :selected [:state :selected]}}}
                   "tree.ui.edn")
        node (:nodes compiled)]
    (is (= :pui4 (:magic compiled)))
    (is (= :scroll (:type node)))
    (is (= 1 (:blueprint-id node)))
    (is (= {:blueprint-id 1 :edit-policy :replace-only
            :props-schema {:items :binding :selected :binding}
            :slot-schema {}}
           (get-in compiled [:boundaries :tree])))
    (is (not (contains? node :component)))
    (is (nil? (get-in compiled [:blueprint-catalog 1 :name])))))

(deftest rejects-unsupported-primitive
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported primitive"
                        (artifact/compile-source
                         {:ui/schema 1
                          :view/id :academy/test/bad
                          :root {:type :legacy-template}}
                         "bad.ui.edn"))))

(deftest rejects-missing-source-schema
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"required source schema 1"
                        (artifact/compile-source
                         {:view/id :academy/test/missing-schema
                          :root {:type :rect}}
                         "missing-schema.ui.edn"))))

(deftest accepts-glow-line-primitive
  (let [compiled (artifact/compile-source
                   {:ui/schema 1
                    :view/id :academy/test/glow
                    :root {:type :glow-line
                           :bind {:x0 [:state :gx0] :x1 [:state :gx1]
                                  :line-y [:state :gy]}
                           :semantics {:role :image}}}
                   "glow.ui.edn")]
    (is (= :glow-line (get-in compiled [:nodes :type])))
    (is (= 3 (count (:bindings compiled))))))
(deftest canonicalization-is-deterministic
  (let [a (artifact/compile-source
           {:ui/schema 1 :root {:type :rect :style {:z 1 :a 2}}
            :view/id :academy/test/hash}
           "a.ui.edn")
        b (artifact/compile-source
           {:ui/schema 1 :view/id :academy/test/hash
            :root {:style {:a 2 :z 1} :type :rect}}
           "a.ui.edn")]
    (is (= (:source-hash a) (:source-hash b)))
    (is (= a b))))
