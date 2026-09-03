(ns cn.li.node.diagnostics-test
  "compile!/diagnostics dual entry point (cn.li.node.compile): compile!
   throws on the first error (used at build/load time); diagnostics
   collects every error in one pass instead of stopping at the first,
   which is what an editor showing a half-broken graph needs."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.test-fixtures :as fx]))

(def ^:private multi-error-text
  "{:ability :many-errors :tunables {}
    :do [(target/raycast {:from ?caster/eye :dir ?caster/eye :bogus-param 1})
         (cooldown/start {:name :main :ticks $missing-tunable})
         (unbound-local-here)
         (finish {:outcome :performed})]}")

(deftest diagnostics-collects-every-error-in-one-pass-test
  (let [doc (surface/parse multi-error-text)
        {:keys [ir diagnostics]} (compile/diagnostics doc fx/opts)]
    (testing "no usable IR when there are errors"
      (is (nil? ir)))
    (testing "collects the unknown param, the missing required param it
              implies, the unknown tunable, and the unbound local/unknown
              call -- not just the first one encountered"
      (is (>= (count diagnostics) 3)
          (str "expected multiple diagnostics, got: " diagnostics))
      (is (some #(= :unknown-param (:code %)) diagnostics))
      (is (some #(= :unknown-tunable (:code %)) diagnostics)))
    (testing "every diagnostic carries a :severity and a human :message"
      (doseq [d diagnostics]
        (is (= :error (:severity d)))
        (is (string? (:message d)))))))

(deftest diagnostics-mode-and-throw-mode-agree-on-a-clean-doc-test
  (let [doc (surface/parse fx/thunder-bolt-text)
        {:keys [ir diagnostics]} (compile/diagnostics doc fx/opts)
        thrown-ir (compile/compile! doc fx/opts)]
    (is (empty? diagnostics))
    (is (= thrown-ir ir))))

(deftest diagnostics-mode-does-not-throw-test
  (let [doc (surface/parse multi-error-text)]
    ;; compile! on the same doc must throw -- diagnostics must not.
    (is (thrown? clojure.lang.ExceptionInfo (compile/compile! doc fx/opts)))
    (is (map? (compile/diagnostics doc fx/opts)))))
