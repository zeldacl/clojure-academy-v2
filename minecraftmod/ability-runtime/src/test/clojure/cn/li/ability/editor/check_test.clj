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
