(ns cn.li.ability.editor.check-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.check :as check]
            [cn.li.node.surface :as surface]
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
             "{:ability :t :do [(let x (vec3/add ?caster/eye 1.0)) (finish {:outcome :performed})]}")
        diags (check/diagnostics raw fx/opts)]
    (is (seq diags))
    (is (= :type-mismatch (:code (first diags))))
    (is (false? (check/ok? raw fx/opts)))
    (is (nil? (check/cost-summary raw fx/opts)))))

(deftest collect-mode-surfaces-every-error-not-just-the-first-test
  (let [raw (surface/read-doc
             "{:ability :t :do [(target/raycast {:from $nope :dir $also-nope :distance $range})
                                 (finish {:outcome :performed})]}")
        diags (check/diagnostics raw fx/opts)]
    (is (>= (count diags) 2))))

(def ^:private effect-catalog
  {:arc-strike {:user-types {:start :vec3 :end :vec3}}})

(deftest unknown-vfx-fields-finds-the-typo-test
  (let [node {:stmt :vfx! :effect-id :arc-strike :fields {:start "n1" :ende "n2"}}]
    (is (= #{:ende} (check/unknown-vfx-fields node effect-catalog)))))

(deftest unknown-vfx-fields-empty-when-every-field-is-declared-test
  (let [node {:stmt :vfx! :effect-id :arc-strike :fields {:start "n1" :end "n2"}}]
    (is (= #{} (check/unknown-vfx-fields node effect-catalog)))))

(deftest unknown-vfx-fields-nil-when-effect-id-is-not-in-the-catalog-test
  (let [node {:stmt :vfx! :effect-id :no-such-effect :fields {:start "n1"}}]
    (is (nil? (check/unknown-vfx-fields node effect-catalog)))))

(deftest unknown-vfx-fields-throws-on-a-non-vfx-node-test
  (is (thrown? clojure.lang.ExceptionInfo (check/unknown-vfx-fields {:stmt :call} effect-catalog))))
