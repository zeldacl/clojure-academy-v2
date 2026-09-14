(ns cn.li.node.types-bank-test
  "cn.li.node.types additions for the surface-DSL compiler: bank/width/
   integral?/assignable?. Named distinctly from any hypothetical
   types_test.clj (none currently exists) purely so it reads clearly as
   covering the NEW register-bank plumbing, not the pre-existing lattice."
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest condition-type-accepts-boolean-and-nullable-handles-test
  ;; Before vocab returns were typed, every host query result was :any, so
  ;; `(if (target/saved-location ...) ...)` type-checked as a side effect of
  ;; the escape hatch rather than because the language allowed it. Typing
  ;; the returns made that read as a type error, which it is not: the
  ;; lattice cannot say "destination or nil" and that node genuinely
  ;; returns nil when the name is not saved.
  (is (true? (types/condition-type? :boolean)))
  (is (true? (types/condition-type? :any)))
  (is (true? (types/condition-type? :destination)) "a nullable host handle")
  (is (true? (types/condition-type? :hit-result)))
  (is (true? (types/condition-type? :entity-ref)))
  (testing "and :vec3, which is exactly the location-teleport case"
    ;; :target/saved-location returns a vec3-shaped map or nil. Both uses
    ;; in that skill are legitimate: `(if loc ...)` and `(vec3/distance
    ;; here loc)`. Any rule that allowed only opaque handles would have
    ;; forced one of the two to be wrong.
    (is (true? (types/condition-type? :vec3)))))

(deftest condition-type-rejects-registers-that-cannot-hold-nil-test
  ;; The half that earns the rule: a :doubles/:longs slot is a primitive
  ;; JVM array element, so a condition reading one is constant-true --
  ;; always an authoring bug, never a nil check.
  (is (false? (types/condition-type? :double)))
  (is (false? (types/condition-type? :long)))
  (testing "a list is the one boxed exception -- an EMPTY vector is truthy"
    (is (false? (types/condition-type? [:list-of :entity-ref]))))
  (testing "and it is not a backdoor into assignable?"
    (is (false? (types/assignable? :destination :boolean))
        "a handle still must not flow into a :boolean-typed parameter")
    (is (false? (types/assignable? :vec3 :boolean)))))

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
