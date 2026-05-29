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

(deftest hand-effect-stateful-enqueue-and-tick-test
  (hand-effects/register-hand-effect!
    :stateful/a
    {:initial-state {:count 0 :last nil}
     :enqueue-state-fn (fn [state payload]
          (-> (or state {:count 0 :last nil})
         (update :count (fnil inc 0))
         (assoc :last payload)))
     :tick-state-fn (fn [state]
       (-> (or state {:count 0 :last nil})
           (update :count (fnil inc 0))))
     :transform-fn (fn [] nil)})

  (is (= {:count 0 :last nil}
    (hand-effects/effect-state-snapshot :stateful/a)))

  (hand-effects/enqueue-hand-effect! :stateful/a {:mode :start :owner-key [:ctx "ctx-a"]})
  (is (= {:count 1 :last {:mode :start :owner-key [:ctx "ctx-a"]}}
    (hand-effects/effect-state-snapshot :stateful/a)))

  (hand-effects/tick-hand-effects!)
  (is (= {:count 2 :last {:mode :start :owner-key [:ctx "ctx-a"]}}
    (hand-effects/effect-state-snapshot :stateful/a)))

  (hand-effects/update-effect-state! :stateful/a assoc :tag :ok)
  (is (= :ok (get (hand-effects/effect-state-snapshot :stateful/a) :tag)))

  (hand-effects/reset-hand-effect-state-for-test! :stateful/a {:count 7})
  (is (= {:count 7}
    (hand-effects/effect-state-snapshot :stateful/a))))
