(ns cn.li.ac.ability.client.level-effects-runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(defn- reset-fixture [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (try
    (f)
    (finally
      (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- handler
  [ops]
  {:enqueue-state-fn (fn [state _ _ _ _] state)
   :tick-state-fn (fn [state] state)
   :build-plan-fn (fn [_ _ _] {:ops ops})})

(deftest level-effect-plan-and-freeze-behavior-test
  (level-effects/register-level-effect! :a (handler [{:op :a}]))
  (level-effects/register-level-effect! :a (handler [{:op :ignored-duplicate}]))
  (level-effects/register-level-effect! :b (handler [{:op :b}]))
  (is (= [:a :b] (:order (level-effects/level-effect-registry-snapshot))))
  (is (= {:ops [{:op :a} {:op :b}]
          :local-walk-speed nil}
         (level-effects/build-level-effect-plan nil nil 0)))
  (level-effects/freeze-level-effect-registry!)
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"frozen"
        (level-effects/register-level-effect! :c (handler [{:op :c}])))))
