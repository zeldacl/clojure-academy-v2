(ns cn.li.ac.ability.client.hand-effects-runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]))

(defn- reset-fixture [f]
  (hand-effects/call-with-camera-pitch-runtime
    (hand-effects/create-camera-pitch-runtime)
    (fn []
      (hand-effects/call-with-hand-effect-runtime
        (hand-effects/create-hand-effect-runtime)
        (fn []
          (hand-effects/reset-hand-effect-registry-for-test!)
          (try
            (f)
            (finally
              (hand-effects/reset-hand-effect-registry-for-test!))))))))

(use-fixtures :each reset-fixture)

(defn- handler
  [id]
  {:enqueue-fn (fn [_] nil)
   :tick-fn (fn [] nil)
   :transform-fn (fn [] {:source id})})

(deftest hand-effect-runtime-isolation-test
  (let [runtime-a (hand-effects/create-hand-effect-runtime)
        runtime-b (hand-effects/create-hand-effect-runtime)]
    (hand-effects/call-with-hand-effect-runtime
      runtime-a
      (fn []
        (hand-effects/register-hand-effect! :iso/a (handler :a))
        (is (= [:iso/a] (:order (hand-effects/hand-effect-registry-snapshot))))))
    (hand-effects/call-with-hand-effect-runtime
      runtime-b
      (fn []
        (is (empty? (:order (hand-effects/hand-effect-registry-snapshot))))
        (hand-effects/register-hand-effect! :iso/b (handler :b))
        (is (= [:iso/b] (:order (hand-effects/hand-effect-registry-snapshot))))))
    (hand-effects/call-with-hand-effect-runtime
      runtime-a
      (fn []
        (is (= [:iso/a] (:order (hand-effects/hand-effect-registry-snapshot))))))))

(deftest hand-effect-transform-and-freeze-test
  (hand-effects/register-hand-effect! :a (handler :a))
  (hand-effects/register-hand-effect! :b (handler :b))
  (is (= {:source :a}
         (hand-effects/current-hand-transform)))
  (hand-effects/freeze-hand-effect-registry!)
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"frozen"
        (hand-effects/register-hand-effect! :c (handler :c)))))
