(ns cn.li.node.types-bank-test
  "cn.li.node.types additions for the surface-DSL compiler: bank/width/
   integral?/assignable?. Named distinctly from any hypothetical
   types_test.clj (none currently exists) purely so it reads clearly as
   covering the NEW register-bank plumbing, not the pre-existing lattice."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.types :as types]))

(deftest bank-assignment-test
  (is (= :doubles (types/bank :double)))
  (is (= :longs (types/bank :long)))
  (is (= :booleans (types/bank :boolean)))
  (is (= :objects (types/bank :vec3)))
  (is (= :objects (types/bank :any)))
  (is (= :objects (types/bank :entity-ref))))

(deftest width-test
  (is (= 3 (types/width :vec3)))
  (is (= 4 (types/width :color)))
  (is (= 1 (types/width :double))))

(deftest integral-test
  (is (types/integral? :long))
  (is (types/integral? :boolean))
  (is (not (types/integral? :double)))
  (is (not (types/integral? :vec3))))

(deftest assignable-test
  (is (types/assignable? :long :double) "int widens to float")
  (is (not (types/assignable? :double :long)) "float does not narrow to int")
  (is (types/assignable? :any :vec3) "field-access results (:any) satisfy any declared type")
  (is (types/assignable? :vec3 :any) "anything satisfies a declared :any param")
  (is (types/assignable? :double :double))
  (is (not (types/assignable? :vec3 :double)))
  (is (not (types/assignable? :keyword :entity-ref))))

(deftest assert-payload-literals-map-keys-test
  (let [specs {:ring-radius {:type :any :map-keys {:from :double :to :double}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"map-keys"
                          (types/assert-payload-literals! :beam-arc-fade specs
                                                          {:ring-radius 0.34})))
    (is (nil? (types/assert-payload-literals! :beam-arc-fade specs
                                              {:ring-radius {:from 0.34 :to 0.34}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing"
                          (types/assert-payload-literals! :beam-arc-fade specs
                                                          {:ring-radius {:from 0.34}})))))
