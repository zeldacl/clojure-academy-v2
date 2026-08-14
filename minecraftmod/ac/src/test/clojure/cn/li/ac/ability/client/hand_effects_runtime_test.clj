(ns cn.li.ac.ability.client.hand-effects-runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.client.vfx-runtime :as vfx-hand]))

(defn- reset-fixture [f]
  (vfx-hand/reset-hand-effect-registry-for-test!)
  (try
    (f)
    (finally
      (vfx-hand/reset-hand-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- handler
  [id]
  {:enqueue-state-fn (fn [state _ _ _ _] state)
   :tick-state-fn (fn [state] state)
   :transform-fn (fn [] {:source id})})

(deftest hand-effect-transform-and-freeze-test
  (vfx-hand/register-hand-effect! :a (handler :a))
  (vfx-hand/register-hand-effect! :b (handler :b))
  (is (= {:source :a}
         (vfx-hand/current-hand-transform)))
  (vfx-hand/freeze-hand-effect-registry!)
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"frozen"
        (vfx-hand/register-hand-effect! :c (handler :c)))))

(deftest hand-effect-stateful-enqueue-and-tick-test
  (vfx-hand/register-hand-effect!
    :stateful/a
    {:initial-state {:count 0 :last nil}
     :enqueue-state-fn (fn [state _ctx-id _channel _owner-key payload]
                         (-> (or state {:count 0 :last nil})
                             (update :count (fnil inc 0))
                             (assoc :last payload)))
     :tick-state-fn (fn [state]
                      (-> (or state {:count 0 :last nil})
                          (update :count (fnil inc 0))))
     :transform-fn (fn [] nil)})

  (is (= {:count 0 :last nil}
         (vfx-hand/effect-hand-state :stateful/a)))

  (vfx-hand/enqueue-hand-effect! :stateful/a "ctx-a" :test/channel
                                     {:mode :start}
                                     :owner-key [:ctx "ctx-a"])
  (is (= {:count 1 :last {:mode :start}}
         (vfx-hand/effect-hand-state :stateful/a)))

  (vfx-hand/tick-hand-effects!)
  (is (= {:count 2 :last {:mode :start}}
         (vfx-hand/effect-hand-state :stateful/a)))

  (vfx-hand/update-hand-effect-state! :stateful/a assoc :tag :ok)
  (is (= :ok (get (vfx-hand/effect-hand-state :stateful/a) :tag)))

  (vfx-hand/reset-hand-effect-state-for-test! :stateful/a {:count 7})
  (is (= {:count 7}
         (vfx-hand/effect-hand-state :stateful/a))))
