(ns cn.li.ac.ability.server.patterns-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.patterns :as patterns]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(def ^:private test-op-key :patterns-test/op)

(defn- isolate-pattern-test-op! [f]
  (let [snapshot (effect/effect-op-registry-snapshot)]
    (effect/reset-effect-op-registry-for-test!
     (assoc snapshot :registry (dissoc (:registry snapshot) test-op-key)))
    (try
      (f)
      (finally
        (effect/reset-effect-op-registry-for-test! snapshot)))))

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
                  evt/fire-ability-event! (fn [_] nil)
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
                  evt/fire-ability-event! (fn [_] nil)
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
                  evt/fire-ability-event! (fn [_]
                                            (swap! order conj :perform-event)
                                            nil)
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
      (is (= [:cost :action :stage :fx :perform-event :exp :cooldown :terminate] @order)))))

(deftest instant-on-down-success-emits-skill-perform-event-test
  (let [events* (atom [])]
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _] true)
                  skill-effects/emit-fx! (fn [_ _ _] nil)
                  skill-effects/gain-exp! (fn [_ _] nil)
                  skill-effects/apply-cooldown! (fn [_ _] nil)
                  evt/make-skill-perform-event (fn [uuid skill-id]
                                                 {:event/type evt/EVT-SKILL-PERFORM
                                                  :uuid uuid
                                                  :skill-id skill-id})
                  evt/fire-ability-event! (fn [event]
                                            (swap! events* conj event))
                  ctx/terminate-context! (fn [_ _] nil)]
      (patterns/instant-on-down!
        {:id :arc-gen
         :actions {:perform! (fn [_] nil)}}
        {:ctx-id "ctx-perform" :player-id "p1"})
      (is (= [{:event/type evt/EVT-SKILL-PERFORM
               :uuid "p1"
               :skill-id :arc-gen}]
             @events*)))))

(deftest hold-charge-release-on-up-marks-performed-and-emits-skill-perform-event-test
  (let [ctx*    (atom {:skill-state {:charge-ticks 7 :performed? false}})
        events* (atom [])]
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _] true)
                  skill-effects/emit-fx! (fn [_ _ _] nil)
                  skill-effects/gain-exp! (fn [_ _] nil)
                  skill-effects/apply-cooldown! (fn [_ _] nil)
                  ctx/get-context (fn [_] @ctx*)
                  ctx/update-context! (fn [_ f & args]
                                        (apply swap! ctx* f args))
                  evt/make-skill-perform-event (fn [uuid skill-id]
                                                 {:event/type evt/EVT-SKILL-PERFORM
                                                  :uuid uuid
                                                  :skill-id skill-id})
                  evt/fire-ability-event! (fn [event]
                                            (swap! events* conj event))]
      (patterns/hold-charge-release-on-up!
        {:id :vec-accel
         :actions {:perform! (fn [_] nil)}}
        {:ctx-id "ctx-charge" :player-id "p1"})
      (is (true? (get-in @ctx* [:skill-state :performed?])))
      (is (= [{:event/type evt/EVT-SKILL-PERFORM
               :uuid "p1"
               :skill-id :vec-accel}]
             @events*)))))

(deftest hold-channel-tick-cost-fail-runs-cost-fail-and-terminates-test
  (let [calls (atom [])]
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _]
                                              (swap! calls conj :cost)
                                              false)
                  evt/fire-ability-event! (fn [_] nil)
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

(deftest release-cast-on-up-success-settles-perform-and-end-test
  (let [ctx* (atom {:skill-state {:hold-ticks 9 :performed? false}})
        events* (atom [])
        fx-stages* (atom [])
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        stage-calls* (atom 0)]
    (effect/register-op! test-op-key
                         (fn [evt _]
                           (swap! stage-calls* inc)
                           evt))
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _] true)
                  skill-effects/emit-fx! (fn [_ _ stage]
                                           (swap! fx-stages* conj stage)
                                           nil)
                  skill-effects/gain-exp! (fn [_ _]
                                            (swap! exp-calls* inc)
                                            nil)
                  skill-effects/apply-cooldown! (fn [_ _]
                                                  (swap! cooldown-calls* inc)
                                                  nil)
                  ctx/get-context (fn [_] @ctx*)
                  ctx/update-context! (fn [_ f & args]
                                        (apply swap! ctx* f args))
                  evt/make-skill-perform-event (fn [uuid skill-id]
                                                 {:event/type evt/EVT-SKILL-PERFORM
                                                  :uuid uuid
                                                  :skill-id skill-id})
                  evt/fire-ability-event! (fn [event]
                                            (swap! events* conj event))]
      (patterns/release-cast-on-up!
        {:id :railgun
         :actions {:up! (fn [_] nil)}
         :perform [[test-op-key {}]]}
        {:ctx-id "ctx-release" :player-id "p1"})
      (is (= 1 @stage-calls*))
      (is (= [:perform :end] @fx-stages*))
      (is (= 1 @exp-calls*))
      (is (= 1 @cooldown-calls*))
      (is (true? (get-in @ctx* [:skill-state :performed?])))
      (is (= [{:event/type evt/EVT-SKILL-PERFORM
               :uuid "p1"
               :skill-id :railgun}]
             @events*)))))

(deftest charge-window-on-up-cost-fail-skips-perform-settlement-test
  (let [ctx* (atom {:skill-state {:hold-ticks 5 :performed? false}})
        events* (atom [])
        fx-stages* (atom [])
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        cost-fail-calls* (atom [])
        stage-calls* (atom 0)]
    (effect/register-op! test-op-key
                         (fn [evt _]
                           (swap! stage-calls* inc)
                           evt))
    (with-redefs [skill-effects/apply-cost! (fn [_ _ _] false)
                  skill-effects/emit-fx! (fn [_ _ stage]
                                           (swap! fx-stages* conj stage)
                                           nil)
                  skill-effects/gain-exp! (fn [_ _]
                                            (swap! exp-calls* inc)
                                            nil)
                  skill-effects/apply-cooldown! (fn [_ _]
                                                  (swap! cooldown-calls* inc)
                                                  nil)
                  ctx/get-context (fn [_] @ctx*)
                  ctx/update-context! (fn [_ f & args]
                                        (apply swap! ctx* f args))
                  evt/fire-ability-event! (fn [event]
                                            (swap! events* conj event))]
      (patterns/charge-window-on-up!
        {:id :groundshock
         :actions {:up! (fn [_] nil)
                   :cost-fail! (fn [evt]
                                 (swap! cost-fail-calls* conj evt))}
         :perform [[test-op-key {}]]}
        {:ctx-id "ctx-charge-window" :player-id "p1"})
      (is (= 0 @stage-calls*))
      (is (= [:end] @fx-stages*))
      (is (= 0 @exp-calls*))
      (is (= 0 @cooldown-calls*))
      (is (false? (get-in @ctx* [:skill-state :performed?])))
      (is (empty? @events*))
      (is (= [:up] (mapv :cost-stage @cost-fail-calls*))))))
