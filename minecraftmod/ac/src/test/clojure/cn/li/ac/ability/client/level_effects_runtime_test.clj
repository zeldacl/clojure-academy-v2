(ns cn.li.ac.ability.client.level-effects-runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.client.vfx-runtime :as vfx-level]))

(defn- reset-fixture [f]
  (vfx-level/reset-level-effect-registry-for-test!)
  (try
    (f)
    (finally
      (vfx-level/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- handler
  [ops]
  {:initial-state {:x [1]}
   :enqueue-state-fn (fn [state _ _ _ _] state)
   :tick-state-fn (fn [state] state)
   :build-plan-fn (fn [_ _ _ _] {:ops ops})})

(deftest level-effect-plan-and-freeze-behavior-test
  (vfx-level/register-level-effect! :a (handler [{:op :a}]))
  (vfx-level/register-level-effect! :a (handler [{:op :ignored-duplicate}]))
  (vfx-level/register-level-effect! :b (handler [{:op :b}]))
  (is (= [:a :b] (:order (vfx-level/level-effect-registry-snapshot))))
  (is (= {:ops [{:op :a} {:op :b}]
          :local-walk-speed nil}
         (vfx-level/build-level-effect-plan nil nil 0 nil)))
  (vfx-level/freeze-level-effect-registry!)
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"frozen"
        (vfx-level/register-level-effect! :c (handler [{:op :c}])))))

(deftest idle-effect-never-invokes-build-plan-fn-test
  (let [calls* (atom 0)]
    (vfx-level/register-level-effect!
      :idle {:enqueue-state-fn (fn [state _ _ _ _] state)
             :tick-state-fn (fn [state] state)
             :build-plan-fn (fn [_ _ _ _] (swap! calls* inc) {:ops [{:op :idle}]})})
    (is (false? (vfx-level/any-level-effect-active?)))
    (is (nil? (vfx-level/build-level-effect-plan nil nil 0 nil)))
    (is (zero? @calls*))))

(deftest arc-state-auto-empties-and-goes-idle-test
  (vfx-level/register-level-effect!
    :ttl-one {:initial-state {:items {}}
              :enqueue-state-fn (fn [state _ _ _ payload]
                                   (assoc-in state [:items :o] [payload]))
              :tick-state-fn (fn [state] (update state :items empty))
              :build-plan-fn (fn [_ _ _ _] {:ops [{:op :live}]})})
    (vfx-level/enqueue-level-effect! :ttl-one "ctx" :ch {:v 1} :owner-key :o)
    (is (true? (vfx-level/any-level-effect-active?)))
    (vfx-level/tick-level-effects!)
    (is (nil? (vfx-level/effect-state-snapshot :ttl-one)))
    (is (false? (vfx-level/any-level-effect-active?))))

(deftest tick-state-fn-not-called-while-idle-test
  (let [ticks* (atom 0)]
    (vfx-level/register-level-effect!
      :idle-tick {:enqueue-state-fn (fn [state _ _ _ _] state)
                  :tick-state-fn (fn [state] (swap! ticks* inc) state)
                  :build-plan-fn (fn [_ _ _ _] nil)})
    (vfx-level/tick-level-effects!)
    (vfx-level/tick-level-effects!)
    (is (zero? @ticks*))))

(deftest custom-empty-state-predicate-is-honored-test
  (vfx-level/register-level-effect!
    :scalar-state {:initial-state {:count 0}
                   :empty-state? (fn [state] (zero? (:count state)))
                   :enqueue-state-fn (fn [state _ _ _ _] (update (or state {:count 0}) :count inc))
                   :tick-state-fn (fn [state] state)
                   :build-plan-fn (fn [_ _ _ _] {:ops [{:op :scalar}]})})
  (is (false? (vfx-level/any-level-effect-active?)))
  (vfx-level/enqueue-level-effect! :scalar-state "ctx" :ch {} :owner-key :o)
  (is (true? (vfx-level/any-level-effect-active?)))
  (is (= {:ops [{:op :scalar}] :local-walk-speed nil}
         (vfx-level/build-level-effect-plan nil nil 0 nil))))

(deftest current-fov-offset-aggregates-registered-contributors-test
  (vfx-level/register-level-effect!
    :no-fov {:initial-state {:x [1]}
             :enqueue-state-fn (fn [state _ _ _ _] state)
             :tick-state-fn (fn [state] state)
             :build-plan-fn (fn [_ _ _ _] {:ops []})})
  (vfx-level/register-level-effect!
    :fov-a {:initial-state {:x [1]}
            :enqueue-state-fn (fn [state _ _ _ _] state)
            :tick-state-fn (fn [state] state)
            :build-plan-fn (fn [_ _ _ _] {:ops []})
            :fov-offset-fn (fn [player-uuid]
                             (when (= "p1" (str player-uuid)) 6.5))})
  (vfx-level/register-level-effect!
    :fov-b {:initial-state {:x [1]}
            :enqueue-state-fn (fn [state _ _ _ _] state)
            :tick-state-fn (fn [state] state)
            :build-plan-fn (fn [_ _ _ _] {:ops []})
            :fov-offset-fn (fn [_] 3.0)})
  (is (= 6.5 (vfx-level/current-fov-offset "p1")) "max contribution wins")
  (is (= 3.0 (vfx-level/current-fov-offset "other")) "unmatched contributor falls back to the unconditional one")
  (is (= 3.0 (vfx-level/current-fov-offset nil)) "nil player uuid keeps unconditional contributions"))

(deftest one-effects-throw-does-not-stop-the-others-test
  ;; Both loops run every effect in registration order. Without isolation a
  ;; single throwing effect aborted the loop, so every effect registered after
  ;; it silently stopped advancing (tick) or contributing ops (build-plan)
  ;; while still rendering from stale state — a frozen visual on a skill that
  ;; has nothing to do with the actual bug.
  (vfx-level/reset-effect-failure-reports-for-test!)
  (let [ticked* (atom [])]
    (vfx-level/register-level-effect!
      :boom {:initial-state {:x [1]}
             :enqueue-state-fn (fn [state _ _ _ _] state)
             :tick-state-fn (fn [_] (throw (ex-info "boom" {})))
             :build-plan-fn (fn [_ _ _ _] (throw (ex-info "boom-plan" {})))})
    (vfx-level/register-level-effect!
      :after {:initial-state {:x [1]}
              :enqueue-state-fn (fn [state _ _ _ _] state)
              :tick-state-fn (fn [state] (swap! ticked* conj :after) state)
              :build-plan-fn (fn [_ _ _ _] {:ops [{:op :after}]})})
    (vfx-level/tick-level-effects!)
    (vfx-level/tick-level-effects!)
    (is (= [:after :after] @ticked*)
        "an effect registered after a throwing one must keep ticking")
    (is (= {:ops [{:op :after}] :local-walk-speed nil}
           (vfx-level/build-level-effect-plan nil nil 0 nil))
        "the throwing effect contributes no ops; the rest of the plan survives")
    ;; The throwing effect keeps its last good state rather than being dropped.
    (is (some? (vfx-level/effect-state-snapshot :boom)))))
