(ns cn.li.node.ir-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.ir :as ir]))

(def ^:private valid-ir
  {:ir/version 1 :id :x :tunable-types {} :constants {}
   :entries {:default 0}
   :blocks [{:id 0 :instrs [{:op :finish :nid "n1" :outcome :performed}]}]})

(deftest validate-accepts-a-minimal-valid-ir-test
  (is (= valid-ir (ir/validate! valid-ir))))

(deftest validate-rejects-a-block-with-no-terminator-test
  (is (thrown? clojure.lang.ExceptionInfo
              (ir/validate! (assoc-in valid-ir [:blocks 0 :instrs]
                                      [{:op :pure :nid "n1" :dst [:reg :doubles 0] :fn :math/add :args []}])))))

(deftest validate-rejects-a-terminator-mid-block-test
  (is (thrown? clojure.lang.ExceptionInfo
              (ir/validate! (assoc-in valid-ir [:blocks 0 :instrs]
                                      [{:op :finish :nid "n1" :outcome :performed}
                                       {:op :finish :nid "n2" :outcome :performed}])))))

(deftest validate-rejects-an-out-of-range-jump-target-test
  (is (thrown? clojure.lang.ExceptionInfo
              (ir/validate! (assoc-in valid-ir [:blocks 0 :instrs]
                                      [{:op :jump :nid "n1" :target 99}])))))

(deftest validate-rejects-an-out-of-range-entry-test
  (is (thrown? clojure.lang.ExceptionInfo (ir/validate! (assoc valid-ir :entries {:default 99})))))

(deftest register-shape-predicate-test
  (is (ir/register? [:reg :doubles 0]))
  (is (ir/register? [:const :objects 3]))
  (is (not (ir/register? [:reg :not-a-bank 0])))
  (is (not (ir/register? [:reg :doubles -1])))
  (is (not (ir/register? "not-a-register"))))
