(ns cn.li.ability.editor.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ability.editor.check :as check]
            [cn.li.node.surface :as surface]
            [cn.li.node.types :as types]
            [cn.li.node.test-fixtures :as fx]))

(deftest clean-doc-has-no-diagnostics-and-a-real-cost-test
  (let [raw (surface/read-doc fx/thunder-bolt-text)]
    (is (= [] (check/diagnostics raw fx/opts)))
    (is (true? (check/ok? raw fx/opts)))
    (let [summary (check/cost-summary raw fx/opts)]
      (is (pos? (:complexity summary)))
      (is (contains? summary :effects))
      (is (contains? summary :host-commands)))))

(deftest broken-doc-reports-a-diagnostic-with-a-code-and-no-cost-test
  (let [raw (surface/read-doc
             "{:id :t :do [(let x (vec3/add ?caster/eye 1.0)) (finish {:outcome :performed})]}")
        diags (check/diagnostics raw fx/opts)]
    (is (seq diags))
    (is (= :invalid-literal-shape (:code (first diags))))
    (is (false? (check/ok? raw fx/opts)))
    (is (nil? (check/cost-summary raw fx/opts)))))

(deftest collect-mode-surfaces-every-error-not-just-the-first-test
  (let [raw (surface/read-doc
             "{:id :t :do [(target/raycast {:from $nope :dir $also-nope :distance $range})
                                 (finish {:outcome :performed})]}")
        diags (check/diagnostics raw fx/opts)]
    (is (>= (count diags) 2))))

(deftest wire-type-error-allows-what-the-compiler-allows-test
  (testing "exact match, :any either way, and :long widening"
    (is (nil? (check/wire-type-error :vec3 :vec3 :value)))
    (is (nil? (check/wire-type-error :any :vec3 :value)))
    (is (nil? (check/wire-type-error :entity-ref :any :value)))
    (is (nil? (check/wire-type-error :long :double :value))))
  (testing "an unknown type on either side is always allowed"
    ;; A :local-get source has no statically known type here. Rejecting it
    ;; would block a wire the compiler accepts -- the one failure mode this
    ;; check must not have.
    (is (nil? (check/wire-type-error nil :vec3 :value)))
    (is (nil? (check/wire-type-error :vec3 nil :value)))
    (is (nil? (check/wire-type-error nil nil :condition)))))

(deftest wire-type-error-rejects-real-mismatches-test
  (is (some? (check/wire-type-error :vec3 :double :value)))
  (is (some? (check/wire-type-error :destination :vec3 :value)))
  (is (= :incompatible-pin-types (:code (check/wire-type-error :keyword :entity-ref :value))))
  (testing ":double narrowing is still refused, matching assignable?"
    (is (some? (check/wire-type-error :double :long :value))))
  (testing "a condition is judged on the source alone"
    (is (nil? (check/wire-type-error :destination nil :condition)) "nullable handle")
    (is (nil? (check/wire-type-error :boolean nil :condition)))
    (is (some? (check/wire-type-error :double nil :condition)) "a primitive can never be nil")))

(deftest editor-wire-rejections-are-a-subset-of-compiler-rejections-test
  ;; THE test for this check, and the reason it delegates instead of
  ;; deciding. V4 graphs can be generated without the editor, so an
  ;; editor-only rule protects nothing -- and an editor rule that
  ;; DISAGREES with the compiler is worse than none: it would either block
  ;; a legal graph or bless an illegal one.
  ;;
  ;; Asserted structurally rather than by sampling graphs: for a value
  ;; wire, "the editor rejects" must be exactly "assignable? says no", and
  ;; for a condition, exactly "condition-type? says no" -- the same two
  ;; functions cn.li.node.compile calls. Any divergence, in either
  ;; direction, fails here.
  (let [ts [:double :long :boolean :keyword :string :vec3 :color :any
            :entity-ref :destination :hit-result :block-placement
            [:list-of :entity-ref]]]
    (doseq [from ts to ts]
      (is (= (not (types/assignable? from to))
             (some? (check/wire-type-error from to :value)))
          (str "value wire " from " -> " to " disagrees with assignable?")))
    (doseq [from ts]
      (is (= (not (types/condition-type? from))
             (some? (check/wire-type-error from nil :condition)))
          (str "condition from " from " disagrees with condition-type?")))))

;; The four unknown-vfx-fields tests that used to close this file are gone
;; with the function itself. The rule they covered (a vfx! payload naming a
;; field the target effect does not declare) is not lost -- it is
;; cn.li.node.types' :unknown-vfx-field, covered by
;; cn.li.node.vfx-payload-check-test, and it now applies to every caller
;; instead of only to graphs opened in the editor.
