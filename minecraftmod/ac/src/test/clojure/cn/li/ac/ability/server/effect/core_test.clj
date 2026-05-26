(ns cn.li.ac.ability.server.effect.core-test
       (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.effect.core :as effect]))

(def ^:private test-op-keys #{:test-inc :boom :dbl})

(defn- isolate-test-ops! [f]
       (let [snapshot (effect/effect-op-registry-snapshot)]
              (effect/reset-effect-op-registry-for-test!
               (assoc snapshot :registry (apply dissoc (:registry snapshot) test-op-keys)))
    (effect/register-op! :test-inc
                          (fn [evt {:keys [n]}]
                            (update evt :v + (double (or n 0.0)))))
    (try (f)
                             (finally (effect/reset-effect-op-registry-for-test! snapshot)))))

(use-fixtures :each isolate-test-ops!)

(deftest run-op-and-resolve-params-test
  (is (= {:v 12.0 :k :x}
         (effect/run-op! {:v 10 :k :x} [:test-inc {:n 2}])))
  (is (= {:v 3.0 :a 1 :b 2}
         (effect/run-op! {:v 0 :a 1 :b 2} [:test-inc {:n (fn [e] (+ (:a e) (:b e)))}]))))

(deftest run-op-missing-returns-evt-test
  (is (= {:x 1} (effect/run-op! {:x 1} [:unknown {}]))))

(deftest run-op-exception-returns-evt-test
  (effect/register-op! :boom (fn [_ _] (throw (Exception. "x"))))
  (is (= {:ok true} (effect/run-op! {:ok true} [:boom {}]))))

(deftest run-ops-and-run-stage-test
  (effect/register-op! :dbl (fn [evt _] (update evt :v * 2)))
  (is (= {:v 8.0} (effect/run-ops! {:v 2} [[:test-inc {:n 2}] [:dbl {}]])))
  (is (= {:v 9.0}
         (effect/run-stage! {:perform [[:test-inc {:n 4}]]} {:v 5} :perform)))
  (is (= {:v 1.0}
         (effect/run-stage! {:on-down [[:test-inc {:n 1}]]} {:v 0} :down)))
  (is (= {:v 2.0}
         (effect/run-stage! {:on-tick [[:test-inc {:n 2}]]} {:v 0} :tick)))
  (is (= {:v 3.0}
         (effect/run-stage! {:on-up [[:test-inc {:n 3}]]} {:v 0} :up)))
  (is (= {:v 4.0}
         (effect/run-stage! {:on-abort [[:test-inc {:n 4}]]} {:v 0} :abort)))
  (is (= {:v 10}
         (effect/run-stage! {:perform (fn [e] (assoc e :v 10))} {:v 0} :perform))))

(deftest effect-op-registry-duplicate-and-freeze-policy-test
       (effect/register-op! :dbl (fn [evt _] (assoc evt :op :first)))
       (effect/register-op! :dbl (fn [evt _] (assoc evt :op :second)))
       (is (= {:op :first} (effect/run-op! {} [:dbl {}]))
                     "duplicate op id preserves the first registered function")
       (effect/freeze-effect-op-registry!)
       (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                                                    #"Effect op registry is frozen"
                                                                                    (effect/register-op! :new-op (fn [evt _] evt)))))
