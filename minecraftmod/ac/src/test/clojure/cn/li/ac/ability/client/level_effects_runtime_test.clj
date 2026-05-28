(ns cn.li.ac.ability.client.level-effects-runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(defn- reset-fixture [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (level-effects/reset-level-effect-registry-for-test!)
      (try
        (f)
        (finally
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- handler
  [ops]
  {:enqueue-fn (fn [_] nil)
   :tick-fn (fn [] nil)
   :build-plan-fn (fn [_ _ _] {:ops ops})})

(deftest level-effect-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)]
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (level-effects/register-level-effect! :iso/a (handler [{:op :a}]))
        (is (= [:iso/a] (:order (level-effects/level-effect-registry-snapshot))))))
    (level-effects/call-with-level-effect-runtime
      runtime-b
      (fn []
        (is (empty? (:order (level-effects/level-effect-registry-snapshot))))
        (level-effects/register-level-effect! :iso/b (handler [{:op :b}]))
        (is (= [:iso/b] (:order (level-effects/level-effect-registry-snapshot))))))
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (is (= [:iso/a] (:order (level-effects/level-effect-registry-snapshot))))))))

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
