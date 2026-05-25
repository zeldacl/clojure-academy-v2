(ns cn.li.ac.ability.server.patterns-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.patterns :as patterns]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(def ^:private test-op-key :patterns-test/op)

(defn- isolate-pattern-test-op! [f]
  (let [reg @#'cn.li.ac.ability.server.effect.core/op-registry]
    (swap! reg dissoc test-op-key)
    (try
      (f)
      (finally
        (swap! reg dissoc test-op-key)))))

(use-fixtures :each isolate-pattern-test-op!)

(deftest instant-on-down-cost-success-runs-perform-action-and-stage-test
  (let [action-calls* (atom [])
        stage-calls* (atom 0)
        fx-calls* (atom 0)
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        terminate-calls* (atom [])]
    (effect/register-op! test-op-key
                         (fn [evt _]
                           (swap! stage-calls* inc)
                           evt))
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _] true)
                  skill-effects/emit-fx! (fn [_ _ _] (swap! fx-calls* inc) nil)
                  skill-effects/gain-exp! (fn [_ _] (swap! exp-calls* inc) nil)
                  skill-effects/apply-cooldown! (fn [_ _] (swap! cooldown-calls* inc) nil)
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminate-calls* conj [ctx-id terminate-fn])
                                           nil)]
      (patterns/instant-on-down!
        {:actions {:perform! (fn [evt] (swap! action-calls* conj evt))
                   :cost-fail! (fn [_] (throw (ex-info "should-not-run" {})))}
         :perform [[test-op-key {}]]}
        {:ctx-id "ctx-success" :player-id "p1"})
      (is (= 1 (count @action-calls*)))
      (is (= 1 @stage-calls*))
      (is (= 1 @fx-calls*))
      (is (= 1 @exp-calls*))
      (is (= 1 @cooldown-calls*))
      (is (= [["ctx-success" nil]] @terminate-calls*)))))

(deftest instant-on-down-cost-fail-skips-perform-action-and-stage-test
  (let [action-calls* (atom 0)
        cost-fail-calls* (atom [])
        stage-calls* (atom 0)
        fx-calls* (atom 0)
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        terminate-calls* (atom [])]
    (effect/register-op! test-op-key
                         (fn [evt _]
                           (swap! stage-calls* inc)
                           evt))
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _] false)
                  skill-effects/emit-fx! (fn [_ _ _] (swap! fx-calls* inc) nil)
                  skill-effects/gain-exp! (fn [_ _] (swap! exp-calls* inc) nil)
                  skill-effects/apply-cooldown! (fn [_ _] (swap! cooldown-calls* inc) nil)
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminate-calls* conj [ctx-id terminate-fn])
                                           nil)]
      (patterns/instant-on-down!
        {:actions {:perform! (fn [_] (swap! action-calls* inc))
                   :cost-fail! (fn [evt] (swap! cost-fail-calls* conj evt))}
         :perform [[test-op-key {}]]}
        {:ctx-id "ctx-fail" :player-id "p2"})
      (is (= 0 @action-calls*))
      (is (= 0 @stage-calls*))
      (is (= 0 @fx-calls*))
      (is (= 0 @exp-calls*))
      (is (= 0 @cooldown-calls*))
      (is (= 1 (count @cost-fail-calls*)))
      (is (= :down (:cost-stage (first @cost-fail-calls*))))
      (is (= [["ctx-fail" nil]] @terminate-calls*)))))

(deftest instant-on-down-success-runs-cost-action-stage-fx-exp-cooldown-in-order-test
  (let [order (atom [])]
    (effect/register-op! test-op-key
                         (fn [evt _]
                           (swap! order conj :stage)
                           evt))
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _]
                                              (swap! order conj :cost)
                                              true)
                  skill-effects/emit-fx! (fn [_ _ _]
                                           (swap! order conj :fx)
                                           nil)
                  skill-effects/gain-exp! (fn [_ _]
                                            (swap! order conj :exp)
                                            nil)
                  skill-effects/apply-cooldown! (fn [_ _]
                                                  (swap! order conj :cooldown)
                                                  nil)
                  ctx/terminate-context! (fn [_ctx-id _terminate-fn]
                                           (swap! order conj :terminate)
                                           nil)]
      (patterns/instant-on-down!
        {:actions {:perform! (fn [_] (swap! order conj :action))}
         :perform [[test-op-key {}]]}
        {:ctx-id "ctx-order" :player-id "p1"})
      (is (= [:cost :action :stage :fx :exp :cooldown :terminate] @order)))))

(deftest hold-channel-tick-cost-fail-runs-cost-fail-and-terminates-test
  (let [calls (atom [])]
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _]
                                              (swap! calls conj :cost)
                                              false)
                  skill-effects/emit-fx! (fn [_ _ _]
                                           (swap! calls conj :fx)
                                           nil)
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! calls conj [:terminate ctx-id terminate-fn])
                                           nil)]
      (patterns/hold-channel-on-tick!
        {:actions {:tick! (fn [_] (swap! calls conj :tick))
                   :cost-fail! (fn [evt] (swap! calls conj [:cost-fail (:cost-stage evt)]))}}
        {:ctx-id "ctx-channel" :player-id "p1"})
      (is (= [:cost [:cost-fail :tick] [:terminate "ctx-channel" nil]] @calls)))))
